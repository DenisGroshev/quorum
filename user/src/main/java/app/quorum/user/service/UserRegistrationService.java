package app.quorum.user.service;

import app.quorum.auth.api.TelegramProfile;
import app.quorum.user.domain.User;
import app.quorum.user.repository.UserRepository;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserRegistrationService {

    private final UserRepository userRepository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public UserRegistrationService(
            UserRepository userRepository, Clock clock, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Each step below must commit or roll back on its own: Postgres aborts the
        // whole transaction on a unique-constraint violation, so a retry has to run
        // in a genuinely separate transaction, not just be caught inline.
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public User registerOrSync(TelegramProfile profile) {
        User existing = transactionTemplate.execute(
                status -> userRepository.findByTgUserId(profile.tgUserId()).orElse(null));
        if (existing != null) {
            return sync(existing.getId(), profile);
        }

        try {
            return transactionTemplate.execute(status -> {
                User user = User.registerFromTelegram(profile, clock);
                return userRepository.saveAndFlush(user);
            });
        } catch (DataIntegrityViolationException raceLost) {
            // Another concurrent first-login for the same tg_user_id won the race
            // and committed first; converge onto that row instead of failing.
            User winner = transactionTemplate.execute(
                    status -> userRepository.findByTgUserId(profile.tgUserId()).orElse(null));
            if (winner == null) {
                throw raceLost;
            }
            return sync(winner.getId(), profile);
        }
    }

    private User sync(java.util.UUID userId, TelegramProfile profile) {
        return transactionTemplate.execute(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.syncFromTelegram(profile);
            return userRepository.save(user);
        });
    }
}
