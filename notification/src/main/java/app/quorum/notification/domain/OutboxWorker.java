package app.quorum.notification.domain;

import app.quorum.notification.infra.OutboxRepository;
import app.quorum.telegram.client.TelegramBotClient;
import app.quorum.telegram.client.TelegramSendResult;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final MessageRenderer messageRenderer;
    private final TelegramBotClient telegramBotClient;
    private final Clock clock;
    private final TransactionTemplate fetchTransactionTemplate;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final long paceMillis;

    public OutboxWorker(
            OutboxRepository outboxRepository,
            UserRepository userRepository,
            MessageRenderer messageRenderer,
            TelegramBotClient telegramBotClient,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${quorum.telegram.send-rate-per-sec}") int sendRatePerSec) {
        this.outboxRepository = outboxRepository;
        this.userRepository = userRepository;
        this.messageRenderer = messageRenderer;
        this.telegramBotClient = telegramBotClient;
        this.clock = clock;
        this.fetchTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.paceMillis = 1000L / sendRatePerSec;
    }

    @Scheduled(fixedDelayString = "PT5S")
    public void run() {
        List<OutboxMessage> batch = fetchTransactionTemplate.execute(
                status -> outboxRepository.findReadyToSend(clock.instant(), PageRequest.of(0, BATCH_SIZE)));
        for (OutboxMessage message : batch) {
            processOne(message.getId());
            pace();
        }
    }

    private void processOne(UUID messageId) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> sendOne(messageId));
        } catch (Exception e) {
            log.error("Failed to process outbox message {}, continuing with the rest of the batch", messageId, e);
        }
    }

    private void sendOne(UUID messageId) {
        OutboxMessage message = outboxRepository.findLockedById(messageId).orElseThrow();
        User recipient = userRepository.findById(message.getRecipientUserId()).orElseThrow();
        RenderedMessage rendered = messageRenderer.render(message.getTemplate(), message.getParams());
        TelegramSendResult result = telegramBotClient.sendMessage(
                recipient.getTgUserId(), rendered.text(), rendered.buttonText(), rendered.buttonUrl());
        applyResult(message, recipient, result);
        outboxRepository.save(message);
    }

    private void applyResult(OutboxMessage message, User recipient, TelegramSendResult result) {
        switch (result.outcome()) {
            case SUCCESS -> message.markSent(clock);
            case TRANSIENT_FAILURE -> {
                if (result.retryAfterSeconds() != null) {
                    message.scheduleRetry(clock, Duration.ofSeconds(result.retryAfterSeconds()));
                } else {
                    message.scheduleRetry(clock);
                }
            }
            case PERMANENT_FAILURE -> {
                message.skip();
                recipient.updateSettings(false, null);
                userRepository.save(recipient);
            }
        }
    }

    private void pace() {
        try {
            Thread.sleep(paceMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
