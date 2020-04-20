package io.magj.iamjdbcdriver;

public class PostgreSqlIamAuthJdbcDriverWrapper extends IamAuthJdbcDriverWrapper {

    public static final String SCHEME_NAME = "iampostgresql";
    public static final String DELEGATE_SCHEME_NAME = "postgresql";
    public static final int DEFAULT_PORT = 5432;
    public static final String DELEGATE_DRIVER_CLASS_NAME = "org.postgresql.Driver";

    static {
        initialiseDriverRegistration(new PostgreSqlIamAuthJdbcDriverWrapper());
    }

    public PostgreSqlIamAuthJdbcDriverWrapper() {
        super(SCHEME_NAME, DELEGATE_SCHEME_NAME, DEFAULT_PORT, DELEGATE_DRIVER_CLASS_NAME);
    }
}
