package app.quorum.common.web;

/**
 * Имена атрибутов HTTP-запроса, которыми обмениваются модули поверх стандартного
 * {@code HttpServletRequest} — без прямой compile-time зависимости друг на друга.
 *
 * <p>Например, {@code :app} резолвит язык пользователя из его профиля один раз
 * (в интерсепторе аутентификации) и кладёт его сюда; {@code catalog} читает этот
 * же атрибут через {@code @RequestAttribute}, не зная о существовании модуля
 * {@code user}.
 */
public final class RequestAttributes {

    public static final String CONTENT_LANG_ATTRIBUTE = "quorum.contentLang";

    private RequestAttributes() {}
}
