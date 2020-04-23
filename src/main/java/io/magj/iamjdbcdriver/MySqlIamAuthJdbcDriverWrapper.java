package io.magj.iamjdbcdriver;

public class MySqlIamAuthJdbcDriverWrapper extends IamAuthJdbcDriverWrapper {

    public static final String SCHEME_NAME = "iammysql";
    public static final String DELEGATE_SCHEME_NAME = "mysql";
    public static final int DEFAULT_PORT = 3306;
    public static final String DELEGATE_DRIVER_CLASS_NAME = "com.mysql.jdbc.Driver";

    static {
        initialiseDriverRegistration(new MySqlIamAuthJdbcDriverWrapper(false));
    }

    public MySqlIamAuthJdbcDriverWrapper() {
        this(true);
    }

    public MySqlIamAuthJdbcDriverWrapper(boolean acceptDelegateUrls) {
        super(
                SCHEME_NAME,
                DELEGATE_SCHEME_NAME,
                DEFAULT_PORT,
                DELEGATE_DRIVER_CLASS_NAME,
                acceptDelegateUrls);
    }
}
