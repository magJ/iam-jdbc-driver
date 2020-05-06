package io.magj.iamjdbcdriver;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.auth.profile.internal.securitytoken.RoleInfo;
import com.amazonaws.auth.profile.internal.securitytoken.STSProfileCredentialsServiceProvider;
import com.amazonaws.regions.AwsProfileRegionProvider;
import com.amazonaws.regions.DefaultAwsRegionProviderChain;
import com.amazonaws.services.rds.auth.GetIamAuthTokenRequest;
import com.amazonaws.services.rds.auth.RdsIamAuthTokenGenerator;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class IamAuthJdbcDriverWrapper implements Driver {

    private static final Logger LOGGER = Logger.getLogger(IamAuthJdbcDriverWrapper.class.getName());

    /**
     * The JDBC driver class to delegate calls to, if not already configured.
     *
     * <p>Only required if using a wrapper instance with no pre-configured driver class
     */
    public static final String DELEGATE_DRIVER_CLASS_PROPERTY = "delegateJdbcDriverClass";
    /**
     * The JDBC url scheme of the driver to delegate calls to. This is the portion of the URL after
     * "jdbc:"
     *
     * <p>For example "mysql" or "postgresql"
     *
     * <p>Only required if using a wrapper instance with no pre-configured driver class
     */
    public static final String DELEGATE_DRIVER_SCHEME_NAME_PROPERTY =
            "delegateJdbcDriverSchemeName";
    /**
     * The optional AWS region to use, if not specified, will use the {@link
     * DefaultAwsRegionProviderChain} or a region from a configured profile
     */
    public static final String AWS_REGION_PROPERTY = "awsRegion";
    /** The optional name of an AWS profile to source credentials/region configuration from. */
    public static final String AWS_PROFILE_PROPERTY = "awsProfile";
    /** An optional role ARN to assume before requesting RDS iam credentials */
    public static final String AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY =
            "awsStsCredentialProviderRoleArn";
    /**
     * An optional session name to use if assuming a role. A random session name will be generated
     * if not specified. This option has no effect if {@link #AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY}
     * is not configured
     */
    public static final String AWS_STS_CREDENTIAL_SESSION_NAME_PROPERTY =
            "awsStsCredentialProviderSessionName";
    /**
     * An optional external ID to pass in the assume role call. This option has no effect if {@link
     * #AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY} is not configured
     */
    public static final String AWS_STS_CREDENTIAL_EXTERNAL_ID_PROPERTY =
            "awsStsCredentialProviderExternalId";
    /**
     * An optional AWS access key to use as credentials when requesting an RDS token. This option
     * must be configured in conjunction with {@link #AWS_SECRET_ACCESS_KEY_PROPERTY} or will be
     * ignored
     */
    public static final String AWS_ACCESS_KEY_ID_PROPERTY = "awsAccessKeyId";
    /**
     * An optional AWS secret key to use as credentials when requesting an RDS token. This option
     * must be configured in conjunction with {@link #AWS_ACCESS_KEY_ID_PROPERTY} or will be ignored
     */
    public static final String AWS_SECRET_ACCESS_KEY_PROPERTY = "awsSecretAccessKey";

    public static final String DEFAULT_PASSWORD_PROPERTY = "password";
    public static final String DEFAULT_USER_PROPERTY = "user";
    private static final String JDBC_URL_PREFIX = "jdbc:";

    static {
        initialiseDriverRegistration(new IamAuthJdbcDriverWrapper());
    }

    private final DefaultAwsRegionProviderChain defaultAwsRegionProviderChain =
            new DefaultAwsRegionProviderChain();
    private final DefaultAWSCredentialsProviderChain defaultAWSCredentialsProviderChain =
            DefaultAWSCredentialsProviderChain.getInstance();

    private final String wrapperSchemeName;
    private final String passwordProperty;
    private final String userProperty;
    private final Integer defaultPort;
    private final String driverClassName;
    private final boolean acceptDelegateUrls;

    private Driver delegate;
    private String delegateSchemeName;

    /**
     * Creates a delegateless IAM Auth JDBC wrapper.
     *
     * <p>Delegate driver class must later be configured with {@link
     * #DELEGATE_DRIVER_CLASS_PROPERTY}
     */
    public IamAuthJdbcDriverWrapper() {
        this(null, null, null, null, true);
    }

    public IamAuthJdbcDriverWrapper(
            String wrapperSchemeName,
            String delegateSchemeName,
            Integer defaultPort,
            String driverClassName,
            boolean acceptDelegateUrls) {
        this(
                wrapperSchemeName,
                delegateSchemeName,
                DEFAULT_PASSWORD_PROPERTY,
                DEFAULT_USER_PROPERTY,
                defaultPort,
                driverClassName,
                acceptDelegateUrls);
    }

    public IamAuthJdbcDriverWrapper(
            String wrapperSchemeName,
            String delegateSchemeName,
            String passwordProperty,
            String userProperty,
            Integer defaultPort,
            String driverClassName,
            boolean acceptDelegateUrls) {
        this.wrapperSchemeName = wrapperSchemeName;
        this.delegateSchemeName = delegateSchemeName;
        this.passwordProperty = passwordProperty;
        this.userProperty = userProperty;
        this.defaultPort = defaultPort;
        this.driverClassName = driverClassName;
        this.acceptDelegateUrls = acceptDelegateUrls;
    }

    protected static void initialiseDriverRegistration(IamAuthJdbcDriverWrapper driver) {
        try {
            LOGGER.fine(
                    () ->
                            "Registering IAM driver wrapper with properties: "
                                    + " wrapperSchemeName="
                                    + driver.wrapperSchemeName
                                    + ", delegateSchemeName="
                                    + driver.delegateSchemeName
                                    + ", defaultPort="
                                    + driver.defaultPort
                                    + ", driverClassName="
                                    + driver.driverClassName);
            DriverManager.registerDriver(driver);
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error registering IAM driver wrapper", e);
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Map<String, String> mergeProperties(
            Properties properties, Map<String, String> uriProperties) {
        Map<String, String> merged = new HashMap<>();
        properties.stringPropertyNames().forEach(sp -> merged.put(sp, properties.getProperty(sp)));
        // URI properties take precedence over connection properties.
        // This is in-line with the behavior of JDBC drivers like postgres
        // It also makes sense, since we use URI properties are used in certain situations to
        // resolve the driver, before connection properties are available
        merged.putAll(uriProperties);
        return merged;
    }

    public static Map<String, String> parseQueryString(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> queryParams = new LinkedHashMap<>();
        String query = uri.getQuery();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx < 0) {
                continue;
            }
            try {
                // AWS secret key can contain + sign and URLDecoder will replace + or %20 with space
                // which will render the access key unusable (if it wasn't encoded before)
                // RFC2396 allows + sign in schema URI so as a workaround we will replace + with its
                // encoded counterpart
                queryParams.put(
                        URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(
                                pair.substring(idx + 1).replace("+", "%2B"),
                                StandardCharsets.UTF_8.name()));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        return queryParams;
    }

    @SuppressWarnings("unchecked")
    private static Driver resolveDriver(String driverClassName)
            throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException,
                    InvocationTargetException, InstantiationException {
        Class<Driver> driverClass = (Class<Driver>) Class.forName(driverClassName);
        return driverClass.getDeclaredConstructor().newInstance();
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException {
        assertUrlNotNull(url);
        URI parsed = parseJdbcUrl(url);
        attemptResolveDelegateDriverDetails(parsed);
        if (isWrapperScheme(parsed)) {
            return true;
        } else if (delegate != null && acceptDelegateUrls) {
            return delegate.acceptsURL(url);
        } else {
            return false;
        }
    }

    private void attemptResolveDelegateDriverDetails(URI uri) {
        if (uri != null) {
            Map<String, String> uriProperties = parseQueryString(uri);
            attemptDelegateDriverResolve(uriProperties);
            resolveDelegateSchemeName(uriProperties);
        }
    }

    private boolean isWrapperScheme(URI parsed) {
        return wrapperSchemeName != null
                && parsed != null
                && wrapperSchemeName.equals(parsed.getScheme());
    }

    private String replaceScheme(String url) {
        return url.replaceFirst(Pattern.quote(wrapperSchemeName), delegateSchemeName);
    }

    private void assertUrlNotNull(String url) throws SQLException {
        if (url == null) {
            throw new SQLException(new NullPointerException());
        }
    }

    private URI parseJdbcUrl(String url) {
        if (url == null || !url.startsWith(JDBC_URL_PREFIX)) {
            return null;
        }
        String substring = url.substring(JDBC_URL_PREFIX.length());
        return URI.create(substring);
    }

    @Override
    public Connection connect(String url, Properties connectionProperties) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        URI parsed = parseJdbcUrl(url);

        Map<String, String> properties =
                mergeProperties(connectionProperties, parseQueryString(parsed));
        resolveDelegateDriver(properties);
        resolveDelegateSchemeName(properties);

        try {
            String host = host(parsed);
            int port = port(parsed);
            String rdsIamAuthToken = generateRdsIamAuthToken(host, port, properties);

            connectionProperties.setProperty(passwordProperty, rdsIamAuthToken);
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "RDS IAM auth token generation failed, attempting to call delegate driver without IAM token",
                    e);
        }

        final String connectUrl = isWrapperScheme(parsed) ? replaceScheme(url) : url;

        return delegate.connect(connectUrl, connectionProperties);
    }

    private void resolveDelegateSchemeName(Map<String, String> properties) {
        if (delegateSchemeName != null) {
            return;
        }
        delegateSchemeName = properties.get(DELEGATE_DRIVER_SCHEME_NAME_PROPERTY);
    }

    private void attemptDelegateDriverResolve(Map<String, String> properties) {
        try {
            resolveDelegateDriver(properties);
        } catch (SQLException e) {
            LOGGER.log(Level.FINE, "Attempt to resolve delegate driver failed", e);
        }
    }

    private void resolveDelegateDriver(Map<String, String> properties) throws SQLException {
        if (delegate != null) {
            return;
        }

        String driverToResolve =
                properties.getOrDefault(DELEGATE_DRIVER_CLASS_PROPERTY, driverClassName);
        if (driverToResolve == null) {
            throw new SQLException("No delegate JDBC driver configured");
        }
        try {
            delegate = resolveDriver(driverToResolve);
        } catch (Exception e) {
            throw new SQLException("Unable to load delegate JDBC driver", e);
        }
    }

    private String host(URI uri) throws SQLException {
        if (uri.getHost() != null) {
            return uri.getHost();
        } else {
            throw new SQLException(
                    "No database host specified. IAM Auth requires that a host be specified in the JDBC URL.");
        }
    }

    private int port(URI uri) throws SQLException {
        if (uri.getPort() != -1) {
            return uri.getPort();
        } else if (defaultPort != null) {
            return defaultPort;
        } else {
            throw new SQLException(
                    "No database port specified. IAM Auth requires that either a default port be pre-configured or a port is specified in the JDBC URL.");
        }
    }

    public String generateRdsIamAuthToken(String host, int port, Map<String, String> properties) {

        String usernameProperty = properties.get(userProperty);
        String regionProperty = properties.get(AWS_REGION_PROPERTY);
        String awsProfile = properties.get(AWS_PROFILE_PROPERTY);

        String region = resolveRegion(regionProperty, awsProfile);

        final RdsIamAuthTokenGenerator generator =
                RdsIamAuthTokenGenerator.builder()
                        .credentials(resolveCredentialProvider(properties))
                        .region(region)
                        .build();

        LOGGER.fine(
                "Generating RDS IAM auth token for: Host="
                        + host
                        + ", Port="
                        + port
                        + ", Username="
                        + usernameProperty);
        return generator.getAuthToken(new GetIamAuthTokenRequest(host, port, usernameProperty));
    }

    private String resolveRegion(String regionProperty, String awsProfileProperty) {
        if (regionProperty != null) {
            return regionProperty;
        } else {
            if (awsProfileProperty != null) {
                AwsProfileRegionProvider awsProfileRegionProvider =
                        new AwsProfileRegionProvider(awsProfileProperty);
                if (awsProfileRegionProvider.getRegion() != null) {
                    return awsProfileRegionProvider.getRegion();
                }
            }
        }
        return defaultAwsRegionProviderChain.getRegion();
    }

    private AWSCredentialsProvider resolveCredentialProvider(Map<String, String> properties) {
        String awsProfile = properties.get(AWS_PROFILE_PROPERTY);
        String awsAccessKey = properties.get(AWS_ACCESS_KEY_ID_PROPERTY);
        String awsSecretKey = properties.get(AWS_SECRET_ACCESS_KEY_PROPERTY);

        final AWSCredentialsProvider baseCredentialProvider;
        if (awsAccessKey != null && awsSecretKey != null) {
            baseCredentialProvider =
                    new AWSStaticCredentialsProvider(
                            new BasicAWSCredentials(awsAccessKey, awsSecretKey));
        } else if (awsProfile != null) {
            baseCredentialProvider = new ProfileCredentialsProvider(awsProfile);
        } else {
            baseCredentialProvider = defaultAWSCredentialsProviderChain;
        }

        String assumedRole = properties.get(AWS_STS_CREDENTIAL_ROLE_ARN_PROPERTY);
        String externalId = properties.get(AWS_STS_CREDENTIAL_EXTERNAL_ID_PROPERTY);
        String roleSessionNameProperty = properties.get(AWS_STS_CREDENTIAL_SESSION_NAME_PROPERTY);

        if (assumedRole != null) {
            String roleSessionName =
                    roleSessionNameProperty == null
                            ? "IAM_RDS_JDBC_DRIVER_WRAPPER" + UUID.randomUUID().toString()
                            : roleSessionNameProperty;
            RoleInfo roleInfo =
                    new RoleInfo()
                            .withRoleArn(assumedRole)
                            .withLongLivedCredentialsProvider(baseCredentialProvider)
                            .withExternalId(externalId)
                            .withRoleSessionName(roleSessionName);
            LOGGER.fine(
                    () ->
                            "Assuming role with ARN: "
                                    + assumedRole
                                    + ", and Session Name: "
                                    + roleSessionName);
            return new STSProfileCredentialsServiceProvider(roleInfo);
        } else {
            return baseCredentialProvider;
        }
    }

    @Override
    public int getMajorVersion() {
        if (delegate == null) {
            logDelegateNotInitialised("getMajorValue");
            return -1;
        }
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion() {
        if (delegate == null) {
            logDelegateNotInitialised("getMinorVersion");
            return -1;
        }
        return delegate.getMinorVersion();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        if (delegate == null) {
            logDelegateNotInitialised("getParentLogger");
            throw new SQLFeatureNotSupportedException("Delegate driver not initialised");
        }
        return delegate.getParentLogger();
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties)
            throws SQLException {
        assertUrlNotNull(url);
        attemptResolveDelegateDriverDetails(parseJdbcUrl(url));
        return delegate.getPropertyInfo(url, properties);
    }

    @Override
    public boolean jdbcCompliant() {
        if (delegate == null) {
            logDelegateNotInitialised("jdbcCompliant");
            return false;
        }
        return delegate.jdbcCompliant();
    }

    private void logDelegateNotInitialised(String method) {
        LOGGER.warning(
                "Method "
                        + method
                        + " called, but delegate driver not initialised, returning bogus value");
    }
}
