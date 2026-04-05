package common;

public class Protocolo {

    // Tags da Cena:

    // Autenticação (1-9)
    public static final int TAG_REGISTER = 1;
    public static final int TAG_LOGIN = 2;

    // Eventos (3-9)
    public static final int TAG_ADD_EVENT = 3;
    public static final int TAG_NEW_DAY = 4;
    public static final int TAG_QUERY_QUANTITY = 5;
    public static final int TAG_QUERY_VOLUME = 6;
    public static final int TAG_QUERY_AVG_PRICE = 7;
    public static final int TAG_QUERY_MAX_PRICE = 8;
    public static final int TAG_FILTER_EVENTS = 9;
    // Notificações (10-19)
    public static final int TAG_NOTIFY_SIMULTANEOUS = 10;
    public static final int TAG_NOTIFY_CONSECUTIVE = 11;

    // Códigos para Status:
    public static final byte STATUS_OK = 0;
    public static final byte STATUS_ERROR = 1;
    public static final byte STATUS_NOT_AUTHENTICATED = 2;
    public static final byte STATUS_USER_EXISTS = 3;
    public static final byte STATUS_INVALID_CREDENTIALS = 4;
    public static final byte STATUS_DAY_ENDED = 5;

    // Construtor privado - classe utilitária
    private Protocolo() {}
}
