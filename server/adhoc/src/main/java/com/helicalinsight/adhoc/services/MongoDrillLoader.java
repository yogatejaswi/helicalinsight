
package com.helicalinsight.adhoc.services;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.helicalinsight.datasource.GsonUtility;
import com.helicalinsight.datasource.nosql.NoSQLLoader;
import com.helicalinsight.efw.exceptions.DatabaseConnectionFailedException;
import com.helicalinsight.efw.exceptions.EfwServiceException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;


/**
 * @author Somen
 * Created on 11/15/2017.
 */

@Component("com.helicalinsight.nosql.mongo")
@Scope("prototype")
@Deprecated
public class MongoDrillLoader extends NoSQLLoader {
    private static final Logger logger = LoggerFactory.getLogger(MongoDrillLoader.class);

    @Override
    public boolean loadToMiddleWare(JsonObject formDataJson) {
        JsonObject mongo = new JsonObject();
        String username = formDataJson.get("userName").getAsString();
        String password = formDataJson.get("password").getAsString();
        String jdbcUrl = GsonUtility.optString(formDataJson, "jdbcUrl");
        String host = getHostPort(jdbcUrl, true);
        String port = getHostPort(jdbcUrl, false);
        String storageName = formDataJson.get("name").getAsString();
        String theId = formDataJson.get("theId").getAsString();
        mongo.addProperty("type", "mongo");

        String connectionString = jdbcUrl;
        if (StringUtils.isBlank(connectionString)) {
            connectionString = "mongodb://" + host + ":" + port;
        }
        connectionString = addCredentials(connectionString, username, password);
        mongo.addProperty("connection", connectionString);
        mongo.addProperty("enabled", true);

        String drillStorageUrl = DrillCsvDataSourceCreator.getUrlOfDrill();

        String resourceUrl = drillStorageUrl + "/storage/" + storageName + "_" + theId + ".json";

        JsonObject storageJson = new JsonObject();
        storageJson.addProperty("name", storageName + "_" + theId);
        storageJson.add("config", mongo);

        String result = DrillCsvDataSourceCreator.drillRestApiCall(resourceUrl, "POST", storageJson.toString());
        if (result == null) {
            throw new EfwServiceException("There was some problem creating drill mongo connection");
        } else {
            try {
                JsonObject resultJSON = new Gson().fromJson(result,JsonObject.class);

            } catch (JsonSyntaxException e) {
                throw new EfwServiceException("There was a problem " + result);
            }
        }
        return true;
    }

    private String getHostPort(String uri, boolean isHost) {
        String splitArray[] = uri.split(":");
        if (splitArray.length >= 3) {
            if (isHost)
                return splitArray[1].replace("//", "");
            else
                return splitArray[2].substring(0, splitArray[2].indexOf("/"));
        }
        return "";
    }

    @Override
    public boolean testConnection(JsonObject formData) {
        String uri = GsonUtility.optString(formData, "jdbcUrl");
        String database = GsonUtility.optString(formData, "database");
        if (StringUtils.isEmpty(database)) {
            database = GsonUtility.optString(formData, "databaseName");
        }
        if (StringUtils.isBlank(uri) || StringUtils.isBlank(database)) {
            throw new DatabaseConnectionFailedException(
                    "MongoDB connection failed: MongoDB URL and database are required.");
        }

        try {
            MongoClientSettings.Builder settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(uri));
            String username = GsonUtility.optString(formData, "userName");
            String password = GsonUtility.optString(formData, "password");
            if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
                String authSource = GsonUtility.optString(formData, "authSource");
                if (StringUtils.isBlank(authSource)) {
                    authSource = database;
                }
                settings.credential(createCredential(username, password, authSource,
                        GsonUtility.optString(formData, "authMechanism")));
            }

            int timeout = GsonUtility.optInt(formData, "timeOut");
            int maxWait = GsonUtility.optInt(formData, "maxWait");
            if (timeout > 0) {
                settings.applyToClusterSettings(builder -> builder.connectTimeout(timeout,
                        java.util.concurrent.TimeUnit.MILLISECONDS));
            }
            if (maxWait > 0) {
                settings.applyToClusterSettings(builder -> builder.serverSelectionTimeout(maxWait,
                        java.util.concurrent.TimeUnit.MILLISECONDS));
            }
            if ("true".equalsIgnoreCase(GsonUtility.optString(formData, "ssl"))) {
                settings.applyToSslSettings(builder -> builder.enabled(true));
            }

            try (MongoClient mongoClient = MongoClients.create(settings.build())) {
                MongoDatabase mongoDatabase = mongoClient.getDatabase(database);
                Document ping = mongoDatabase.runCommand(new Document("ping", 1));
                Number pingStatus = ping.get("ok", Number.class);
                if (pingStatus == null || pingStatus.doubleValue() != 1.0d) {
                    throw new DatabaseConnectionFailedException("MongoDB connection failed: ping returned an unsuccessful response.");
                }
                return true;
            }
        } catch (MongoException | IllegalArgumentException exception) {
            String message = StringUtils.defaultIfBlank(exception.getMessage(), exception.getClass().getSimpleName());
            logger.error("MongoDB connection validation failed: {}", message);
            throw new DatabaseConnectionFailedException("MongoDB connection failed: " + message, exception);
        }
    }

    private String addCredentials(String uri, String username, String password) {
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password) || uri.contains("@")) {
            return uri;
        }
        int schemeEnd = uri.indexOf("://");
        if (schemeEnd < 0) {
            return uri;
        }
        String credentials = URLEncoder.encode(username, StandardCharsets.UTF_8)
                + ":" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return uri.substring(0, schemeEnd + 3) + credentials + "@" + uri.substring(schemeEnd + 3);
    }

    private MongoCredential createCredential(String username, String password, String authSource,
                                             String authMechanism) {
        String mechanism = authMechanism == null ? "" : authMechanism.toLowerCase(Locale.ROOT);
        if ("mongocr".equals(mechanism)) {
            return MongoCredential.createMongoCRCredential(username, authSource, password.toCharArray());
        }
        if ("scramsha1".equals(mechanism) || "scram-sha-1".equals(mechanism)) {
            return MongoCredential.createScramSha1Credential(username, authSource, password.toCharArray());
        }
        if ("plain".equals(mechanism)) {
            return MongoCredential.createPlainCredential(username, authSource, password.toCharArray());
        }
        return MongoCredential.createCredential(username, authSource, password.toCharArray());
    }
}

