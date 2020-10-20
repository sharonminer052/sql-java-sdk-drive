/*-
 * Copyright (c) 2011, 2020 Oracle and/or its affiliates.  All rights reserved.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 *  https://oss.oracle.com/licenses/upl/
 */

package oracle.nosql.driver.iam;

import static oracle.nosql.driver.iam.Utils.HEADER_DELIMITER;
import static oracle.nosql.driver.iam.Utils.RSA;
import static oracle.nosql.driver.iam.Utils.SIGNATURE_HEADER_FORMAT;
import static oracle.nosql.driver.iam.Utils.SINGATURE_VERSION;
import static oracle.nosql.driver.iam.Utils.createFormatter;
import static oracle.nosql.driver.iam.Utils.sign;
import static oracle.nosql.driver.util.HttpConstants.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import oracle.nosql.driver.AuthorizationProvider;
import oracle.nosql.driver.NoSQLHandleConfig;
import oracle.nosql.driver.Region;
import oracle.nosql.driver.Region.RegionProvider;
import oracle.nosql.driver.ops.Request;
import oracle.nosql.driver.util.LruCache;

import io.netty.handler.codec.http.HttpHeaders;

/**
 * Cloud service only.
 * <p>
 * An instance of {@link AuthorizationProvider} that generates and caches
 * signature for each request as authorization string. A number of pieces of
 * information are required for configuration. See
 * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm">SDK Configuration File</a>
 * and
 * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/apisigningkey.htm">Required Keys and OCIDs</a>
 * for additional information as well as instructions on how to create required
 * keys and OCIDs for configuration. The required information includes:
 * <ul>
 * <li>A signing key, used to sign requests.</li>
 * <li>A pass phrase for the key, if it is encrypted</li>
 * <li>The fingerprint of the key pair used for signing</li>
 * <li>The OCID of the tenancy</li>
 * <li>The OCID of a user in the tenancy</li>
 * </ul>
 * All of this information is required to authenticate and authorize access to
 * the service.
 * <p>
 * There are two mechanisms for providing authorization information:
 * <ol>
 * <li>Using a user's identity and optional profile. This authenticates and
 * authorizes the application based on a specific user identity.</li>
 * <li>Using an Instance Principal, which can be done when running on a
 * compute instance in the Oracle Cloud Infrastructure (OCI). See
 * {@link #createWithInstancePrincipal} and
 * <a href="https://docs.cloud.oracle.com/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm">Calling Services from Instances</a>.
 * </li>
 * </ol>
 * <p>
 * The latter can be simpler to use when running on an OCI compute instance,
 * but limits the ability to use a compartment name vs OCID when naming
 * compartments and tables in {@link Request} classes and when naming tables
 * in queries. A specific user identity is best for naming flexibility,
 * allowing both compartment names and OCIDs.
 * <p>
 * When using a specific user's identity there are several options to provide
 * the required information:
 * <ul>
 * <li>Using a configuration file. See
 * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm">SDK Configuration File</a>
 * for details on the file contents. By default the file is stored in
 * ~/.oci/config, but you may supply a path to another location. The
 * configuration file may include multiple profiles. The constructors that use
 * a configuration include
 * <ul>
 * <li>{@link #SignatureProvider()}</li>
 * <li>{@link #SignatureProvider(String)}</li>
 * <li>{@link #SignatureProvider(String, String)}</li>
 * </ul>
 * </li>
 * <li>Using information passed programmatically. The constructors that use this
 * mechanism include
 * <ul>
 * <li>{@link #SignatureProvider(String, String, String, String, char[])}</li>
 * <li>{@link #SignatureProvider(String, String, String, File, char[])}</li>
 * </ul>
 * </li>
 * </ul>
 */
public class SignatureProvider
    implements AuthorizationProvider, RegionProvider {

    private static final String SIGNING_HEADERS = "(request-target) host date";

    /* Cache key name */
    private static final String CACHE_KEY = "signature";

    /* Maximum lifetime of signature 300 seconds */
    protected static final int MAX_ENTRY_LIFE_TIME = 300;

    /* Default refresh time before signature expiry, 10 seconds*/
    protected static final int DEFAULT_REFRESH_AHEAD = 10000;

    /* User profile and key providers */
    private final AuthenticationProfileProvider provider;
    private final PrivateKeyProvider privateKeyProvider;

    private final LruCache<String, SignatureDetails> signatureCache;

    /* Refresh timer */
    private volatile Timer refresher;

    /* Refresh time before signature expired from cache */
    private long refreshAheadMs = DEFAULT_REFRESH_AHEAD;

    /* Refresh interval, if zero, no refresh will be scheduled */
    private long refreshIntervalMs = 0;
    private String serviceHost;
    private Region region;
    private Logger logger;

    /**
     * Creates a SignatureProvider using a default configuration file and
     * profile. The configuration file used is <code>~/.oci/config</code>. See
     * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm">SDK Configuration File</a>
     * for details of the file's contents and format.
     *
     * @throws IOException if error loading profile from OCI configuration file
     */
    public SignatureProvider() throws IOException {
        this(new OCIConfigFileProvider());
    }

    /**
     * Creates a SignatureProvider using the specified profile. The
     * configuration file used is <code>~/.oci/config</code>. See
     * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm">SDK Configuration File</a>
     * for details of the file's contents and format
     *
     * @param profileName user profile name
     *
     * @throws IOException if error loading profile from OCI configuration file
     */
    public SignatureProvider(String profileName) throws IOException {
        this(new OCIConfigFileProvider(profileName));
    }

    /**
     * Creates a SignatureProvider using the specified config file and
     * profile. See
     * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/sdkconfig.htm">SDK Configuration File</a>
     * for details of the file's contents and format
     *
     * @param configFile path of configuration file
     *
     * @param profileName user profile name
     *
     * @throws IOException if error loading profile from OCI configuration file
     */
    public SignatureProvider(String configFile, String profileName)
        throws IOException {

        this(new OCIConfigFileProvider(configFile, profileName));
    }

    /**
     * Creates a SignatureProvider using directly provided user authentication
     * information. See
     * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/apisigningkey.htm">Required Keys and OCIDs</a>
     * for details of the required parameters.
     *
     * @param tenantId tenant id
     *
     * @param userId user id
     *
     * @param fingerprint fingerprint of the key being used
     *
     * @param privateKey the string of private key used to sign request
     *
     * @param passphrase optional passphrase for the (encrypted) private key
     */
    public SignatureProvider(String tenantId,
                             String userId,
                             String fingerprint,
                             String privateKey,
                             char[] passphrase) {

        this(SimpleProfileProvider.builder()
             .tenantId(tenantId)
             .userId(userId)
             .fingerprint(fingerprint)
             .passphrase(passphrase)
             .privateKeySupplier(new PrivateKeyStringSupplier(privateKey))
             .build());
    }

    /**
     * Creates a SignatureProvider using directly provided user authentication
     * information. See
     * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/apisigningkey.htm">Required Keys and OCIDs</a>
     * for details of the required parameters.
     *
     * @param tenantId tenant id
     *
     * @param userId user id
     *
     * @param fingerprint fingerprint of the key being used
     *
     * @param privateKeyFile the file of the private key used to sign request
     *
     * @param passphrase optional passphrase for the (encrypted) private key
     */
    public SignatureProvider(String tenantId,
                             String userId,
                             String fingerprint,
                             File privateKeyFile,
                             char[] passphrase) {

        this(SimpleProfileProvider.builder()
             .tenantId(tenantId)
             .userId(userId)
             .fingerprint(fingerprint)
             .passphrase(passphrase)
             .privateKeySupplier(new PrivateKeyFileSupplier(privateKeyFile))
             .build());
    }

    /**
     * Creates a SignatureProvider using directly provided user authentication
     * information. See
     * <a href="https://docs.cloud.oracle.com/iaas/Content/API/Concepts/apisigningkey.htm">Required Keys and OCIDs</a>
     * for details of the required parameters.
     *
     * @param tenantId tenant id
     *
     * @param userId user id
     *
     * @param fingerprint fingerprint of the key being used
     *
     * @param privateKeyFile the file of the private key used to sign request
     *
     * @param passphrase optional passphrase for the (encrypted) private key
     *
     * @param region identifies the region will be accessed by the NoSQLHandle.
     */
    public SignatureProvider(String tenantId,
                             String userId,
                             String fingerprint,
                             File privateKeyFile,
                             char[] passphrase,
                             Region region) {

        this(SimpleProfileProvider.builder()
             .tenantId(tenantId)
             .userId(userId)
             .fingerprint(fingerprint)
             .passphrase(passphrase)
             .privateKeySupplier(new PrivateKeyFileSupplier(privateKeyFile))
             .region(region)
             .build());
    }

    /**
     * Creates a SignatureProvider using an instance principal. This
     * constructor may be used when calling the Oracle NoSQL Database
     * Cloud Service from an Oracle Cloud compute instance.
     * It authenticates with the instance principal and
     * uses a security token issued by IAM to do the actual request signing.
     * <p>
     * When using an instance principal the compartment id (OCID) must be
     * specified on each request or defaulted by using
     * {@link NoSQLHandleConfig#setDefaultCompartment}. If the compartment id
     * is not specified for an operation an exception will be thrown.
     * <p>
     * See <a href="https://docs.cloud.oracle.com/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm">Calling Services from Instances</a>.
     * @return SignatureProvider
     */
    public static SignatureProvider createWithInstancePrincipal() {
        return new SignatureProvider(
            InstancePrincipalsProvider.builder().build());
    }

    /**
     * Creates a SignatureProvider using an instance principal. This
     * constructor may be used when calling the Oracle NoSQL Database
     * Cloud Service from an Oracle Cloud compute instance.
     * It authenticates with the instance principal and
     * uses a security token issued by IAM to do the actual request signing.
     * <p>
     * When using an instance principal the compartment id (OCID) must be
     * specified on each request or defaulted by using
     * {@link NoSQLHandleConfig#setDefaultCompartment}. If the compartment id
     * is not specified for an operation an exception will be thrown.
     * <p>
     * See <a href="https://docs.cloud.oracle.com/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm">Calling Services from Instances</a>.
     *
     * @param region identifies the region will be accessed by the NoSQLHandle.
     *
     * @return SignatureProvider
     */
    public static SignatureProvider createWithInstancePrincipal(Region region) {
        return new SignatureProvider(
            InstancePrincipalsProvider.builder().setRegion(region).build());
    }

    /**
     * Creates a SignatureProvider using an instance principal. This
     * constructor may be used when calling the Oracle NoSQL Database
     * Cloud Service from an Oracle Cloud compute instance.
     * It authenticates with the instance principal and
     * uses a security token issued by IAM to do the actual request signing.
     * <p>
     * When using an instance principal the compartment id (OCID) must be
     * specified on each request or defaulted by using
     * {@link NoSQLHandleConfig#setDefaultCompartment}. If the compartment id
     * is not specified for an operation an exception will be thrown.
     * <p>
     * See <a href="https://docs.cloud.oracle.com/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm">Calling Services from Instances</a>.
     *
     * @param iamAuthUri The URI is usually detected automatically, specify
     * the URI if you need to overwrite the default, or encounter the
     * <code>Invalid IAM URI</code> error.
     *
     * @return SignatureProvider
     */
    public static SignatureProvider
        createWithInstancePrincipal(String iamAuthUri) {

        return new SignatureProvider(
            InstancePrincipalsProvider.builder()
            .setFederationEndpoint(iamAuthUri)
            .build());
    }

    /**
     * Creates a SignatureProvider using an instance principal. This
     * constructor may be used when calling the Oracle NoSQL Database
     * Cloud Service from an Oracle Cloud compute instance.
     * It authenticates with the instance principal and
     * uses a security token issued by IAM to do the actual request signing.
     * <p>
     * When using an instance principal the compartment id (OCID) must be
     * specified on each request or defaulted by using
     * {@link NoSQLHandleConfig#setDefaultCompartment}. If the compartment id
     * is not specified for an operation an exception will be thrown.
     * <p>
     * See <a href="https://docs.cloud.oracle.com/iaas/Content/Identity/Tasks/callingservicesfrominstances.htm">Calling Services from Instances</a>.
     *
     * @param iamAuthUri The URI is usually detected automatically, specify
     * the URI if you need to overwrite the default, or encounter the
     * <code>Invalid IAM URI</code> error.
     * @param region the region to use, it may be null
     * @param logger the logger used by the SignatureProvider.
     *
     * @return SignatureProvider
     */
    public static SignatureProvider
        createWithInstancePrincipal(String iamAuthUri,
                                    Region region,
                                    Logger logger) {

        SignatureProvider provider = new SignatureProvider(
            InstancePrincipalsProvider.builder()
            .setFederationEndpoint(iamAuthUri)
            .setLogger(logger)
            .setRegion(region)
            .build());
        provider.setLogger(logger);
        return provider;
    }

    /**
     * Creates a SignatureProvider using a resource principal. This
     * constructor may be used when calling the Oracle NoSQL Database
     * Cloud Service from other Oracle Cloud service resource such as
     * Functions. It uses a resource provider session token (RPST) that
     * enables the resource such as function to authenticate itself.
     * <p>
     * When using an resource principal the compartment id (OCID) must be
     * specified on each request or defaulted by using
     * {@link NoSQLHandleConfig#setDefaultCompartment}. If the compartment id
     * is not specified for an operation an exception will be thrown.
     * <p>
     * See <a href="https://docs.cloud.oracle.com/en-us/iaas/Content/Functions/Tasks/functionsaccessingociresources.htm">Accessing Other Oracle Cloud Infrastructure Resources from Running Functions</a>.
     *
     * @return SignatureProvider
     */
    public static SignatureProvider createWithResourcePrincipal() {
        return SignatureProvider.createWithResourcePrincipal(null);
    }

    /**
     * Creates a SignatureProvider using a resource principal. This
     * constructor may be used when calling the Oracle NoSQL Database
     * Cloud Service from other Oracle Cloud Service resource such as
     * Functions. It uses a resource provider session token (RPST) that
     * enables the resource such as the function to authenticate itself.
     *
     * <p>
     * When using an resource principal the compartment id (OCID) must be
     * specified on each request or defaulted by using
     * {@link NoSQLHandleConfig#setDefaultCompartment}. If the compartment id
     * is not specified for an operation an exception will be thrown.
     * <p>
     * See <a href="https://docs.cloud.oracle.com/en-us/iaas/Content/Functions/Tasks/functionsaccessingociresources.htm">Accessing Other Oracle Cloud Infrastructure Resources from Running Functions</a>.
     *
     * @param logger the logger used by the SignatureProvider
     *
     * @return SignatureProvider
     */
    public static SignatureProvider createWithResourcePrincipal(Logger logger) {
        SignatureProvider provider = new SignatureProvider(
            ResourcePrincipalProvider.build(logger));
        provider.setLogger(logger);
        return provider;
    }

    /*
     * The SignatureProvider that generates and caches request signature using
     * key id and private key supplied by {@link AuthenticationProfileProvider}.
     */
    protected SignatureProvider(AuthenticationProfileProvider provider) {
        this(provider, MAX_ENTRY_LIFE_TIME, DEFAULT_REFRESH_AHEAD);
    }

    /*
     * The constructor that is able to set refresh time before AT expired,
     * currently this is hidden for simplicity.
     */
    protected SignatureProvider(AuthenticationProfileProvider profileProvider,
                                int durationSeconds,
                                int refreshAhead) {
        if (profileProvider instanceof RegionProvider) {
            this.region = ((RegionProvider) profileProvider).getRegion();
        }
        this.provider = profileProvider;
        this.privateKeyProvider = (provider == null) ?
            null : new PrivateKeyProvider(provider);

        if (durationSeconds > MAX_ENTRY_LIFE_TIME) {
            throw new IllegalArgumentException(
                "Access token cannot be cached longer than " +
                MAX_ENTRY_LIFE_TIME + " seconds");
        }
        this.signatureCache =
            new LruCache<String, SignatureDetails>(2, durationSeconds * 1000);

        this.refreshAheadMs = refreshAhead;
        long durationMS = durationSeconds * 1000;
        if (durationMS > refreshAheadMs) {
            this.refreshIntervalMs = durationMS - refreshAheadMs;
        }
    }

    @Override
    public String getAuthorizationString(Request request) {
        if (serviceHost == null) {
            throw new IllegalArgumentException(
               "Unable to find service host, use setServiceHost " +
               "to load from NoSQLHandleConfig");
        }
        SignatureDetails sigDetails = getSignatureDetails();
        if (sigDetails != null) {
            return sigDetails.getSignatureHeader();
        }
        return null;
    }

    @Override
    public void setRequiredHeaders(String authString,
                                   Request request,
                                   HttpHeaders headers) {

        SignatureDetails sigDetails = getSignatureDetails();
        if (sigDetails == null) {
            return;
        }
        headers.add(AUTHORIZATION, sigDetails.getSignatureHeader());
        headers.add(DATE, sigDetails.getDate());

        String compartment = request.getCompartment();
        if (compartment == null) {
            /*
             * If request doesn't has compartment id, set the tenant id as the
             * default compartment, which is the root compartment in IAM if
             * using user principal. If using an instance principal this
             * value is null.
             */
            compartment = getTenantOCID();
        }

        if (compartment != null) {
            headers.add(REQUEST_COMPARTMENT_ID, compartment);
        } else {
            throw new IllegalArgumentException(
                "Compartment is null. When authenticating using an " +
                "Instance Principal the compartment for the operation " +
                "must be specified.");
        }
    }

    /**
     * Get tenant OCID if using user principal.
     * @return tenant OCID of user
     */
    private String getTenantOCID() {
        if (provider instanceof UserAuthenticationProfileProvider) {
            return ((UserAuthenticationProfileProvider)this.provider)
                .getTenantId();
        }
        return null;
    }

    @Override
    public void close() {
        if (refresher != null) {
            refresher.cancel();
            refresher = null;
        }
    }

    @Override
    public Region getRegion() {
        return region;
    }

    public SignatureProvider setServiceHost(NoSQLHandleConfig config) {
        URL serviceURL = config.getServiceURL();
        if (serviceURL == null) {
            throw new IllegalArgumentException(
                "Must set service URL first");
        }
        this.serviceHost = serviceURL.getHost();
        return this;
    }

    /**
     * Sets a Logger instance for this provider. If not set, the logger
     * associated with the driver is used.
     *
     * @param logger the logger
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Returns the logger of this provider if set, null if not.
     *
     * @return logger
     */
    public Logger getLogger() {
        return logger;
    }

    /**
     * Resource principal session tokens carry JWT claims. Permit the
     * retrieval of the value from the token by given key.
     * See {@link ResourcePrincipalClaimKeys}
     *
     * @param key the name of a claim in the session token
     *
     * @return the claim value.
     */
    public String getResourcePrincipalClaim(String key) {
        if (!(provider instanceof ResourcePrincipalProvider)) {
            throw new IllegalArgumentException(
                "Only resource principal support");
        }
        ResourcePrincipalProvider rpp = (ResourcePrincipalProvider)provider;
        return rpp.getClaim(key);
    }

    private void logMessage(Level level, String msg) {
        if (logger != null) {
            logger.log(level, msg);
        }
    }

    private SignatureDetails getSignatureDetails() {
        SignatureDetails sigDetails = signatureCache.get(CACHE_KEY);
        if (sigDetails != null) {
            return sigDetails;
        }

        sigDetails = getSignatureDetailsInternal();
        signatureCache.put(CACHE_KEY, sigDetails);
        scheduleRefresh();
        return sigDetails;
    }

    private SignatureDetails getSignatureDetailsInternal() {
        String date = createFormatter().format(new Date());
        String keyId = provider.getKeyId();

        if (provider instanceof InstancePrincipalsProvider) {
            privateKeyProvider.reload(provider.getPrivateKey(),
                                      provider.getPassphraseCharacters());
        }
        String signature;
        try {
            signature = sign(signingContent(date), privateKeyProvider.getKey());
        } catch (Exception e) {
            logMessage(Level.SEVERE, "Error signing request " + e.getMessage());
            return null;
        }

        String sigHeader = String.format(SIGNATURE_HEADER_FORMAT,
                                         SIGNING_HEADERS,
                                         keyId,
                                         RSA,
                                         signature,
                                         SINGATURE_VERSION);
        return new SignatureDetails(sigHeader, date);
    }

    String signingContent(String date) {
        StringBuilder sb = new StringBuilder();
        sb.append(REQUEST_TARGET).append(HEADER_DELIMITER)
        .append("post /").append(NOSQL_DATA_PATH).append("\n")
        .append(HOST).append(HEADER_DELIMITER)
        .append(serviceHost).append("\n")
        .append(DATE).append(HEADER_DELIMITER).append(date);
        return sb.toString();
    }

    private void scheduleRefresh() {
        /* If refresh interval is 0, don't schedule a refresh */
        if (refreshIntervalMs == 0) {
            return;
        }
        if (refresher != null) {
            refresher.cancel();
            refresher = null;
        }

        refresher = new Timer(true /* isDaemon */);
        refresher.schedule(new RefreshTask(), refreshIntervalMs);
    }

    private class RefreshTask extends TimerTask {

        @Override
        public void run() {
            try {
                SignatureDetails sigDetails = getSignatureDetailsInternal();
                if (sigDetails != null) {
                    signatureCache.put(CACHE_KEY, sigDetails);
                    scheduleRefresh();
                }
            } catch (Exception e) {
                /*
                 * Ignore the failure of refresh. The driver would try to
                 * generate signature in the next request if signature is not
                 * available, the failure would be reported at that moment.
                 */
                logMessage(Level.WARNING,
                           "Unable to refresh cached request signature, " +
                           e.getMessage());
                refresher.cancel();
                refresher = null;
            }
        }
    }

    static class SignatureDetails {

        /* Signature header string */
        private String signatureHeader;

        /*
         * Signing date, keep it and pass along with each request,
         * so requests can reuse the signature within the 5-mins
         * time window.
         */
        private String date;

        SignatureDetails(String signatureHeader, String date) {
            this.signatureHeader = signatureHeader;
            this.date = date;
        }

        String getDate() {
            return date;
        }

        String getSignatureHeader() {
            return signatureHeader;
        }

        boolean isValid(String header) {
            if (header == null) {
                return false;
            }
            return header.equals(signatureHeader);
        }
   }

    /**
     * Claim keys in the resource principal session token(RPST).
     * <p>
     * They can be used to retrieve resource principal metadata such as its
     * compartment and tenancy OCID.
     */
    public static class ResourcePrincipalClaimKeys {
        /**
         * The claim name that the RPST holds for the resource compartment.
         * This can be passed to
         * {@link SignatureProvider#getResourcePrincipalClaim(String)}
         * to retrieve the resource's compartment OCID.
         */
        public final static String COMPARTMENT_ID_CLAIM_KEY = "res_compartment";

        /**
         * The claim name that the RPST holds for the resource tenancy.
         * This can be passed to
         * {@link SignatureProvider#getResourcePrincipalClaim(String)}
         * to retrieve the resource's tenancy OCID.
         */
        public final static String TENANT_ID_CLAIM_KEY = "res_tenant";
    }
}