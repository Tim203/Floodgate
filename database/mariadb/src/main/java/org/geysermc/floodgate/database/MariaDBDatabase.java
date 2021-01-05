/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.database;

import com.google.inject.Inject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.geysermc.floodgate.api.link.LinkRequest;
import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.config.FloodgateConfig;
import org.geysermc.floodgate.config.FloodgateConfig.PlayerLinkDatabase;
import org.geysermc.floodgate.link.CommonPlayerLink;
import org.geysermc.floodgate.link.LinkRequestImpl;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.mariadb.jdbc.MariaDbPoolDataSource;

public class MariaDBDatabase extends CommonPlayerLink {
    private MariaDbPoolDataSource pool;

    @Inject private FloodgateConfig config;

    @Override
    public void load() {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            PlayerLinkDatabase databaseconfig = config.getPlayerLink().getDatabase();
            pool = new MariaDbPoolDataSource("jdbc:mariadb://" + databaseconfig.getHostname() + "/" +
                    databaseconfig.getDatabase() +
                    "?user=" + databaseconfig.getUsername() + "&password=" + databaseconfig.getPassword() +
                    "&minPoolSize=2&maxPoolSize=10");

            Connection connection = pool.getConnection();
            Statement statement = connection.createStatement();
            statement.setQueryTimeout(25);  // set timeout to 30 sec.
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `LinkedPlayers` ( " +
                            "`bedrockId` BINARY(16) NOT NULL , " +
                            "`javaUniqueId` BINARY(16) NOT NULL , " +
                            "`javaUsername` VARCHAR(16) NOT NULL , " +
                            " PRIMARY KEY (`bedrockId`) , " +
                            " INDEX (`bedrockId`, `javaUniqueId`)" +
                            ") ENGINE = InnoDB;"
            );
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `LinkedPlayersRequest` ( " +
                            "`javaUsername` VARCHAR(16) NOT NULL , `javaUniqueId` BINARY(16) NOT NULL , " +
                            "`linkCode` VARCHAR(16) NOT NULL , " +
                            "`bedrockUsername` VARCHAR(16) NOT NULL ," +
                            "`requestTime` BIGINT NOT NULL , " +
                            " PRIMARY KEY (`javaUsername`), INDEX(`requestTime`)" +
                            " ) ENGINE = InnoDB;"
            );
        } catch (ClassNotFoundException exception) {
            getLogger().error("The required class to load the MariaDB database wasn't found");
        } catch (SQLException exception) {
            getLogger().error("Error while loading database", exception);
        }
    }

    @Override
    public void stop() {
        super.stop();
        pool.close();
    }

    @Override
    public CompletableFuture<LinkedPlayer> getLinkedPlayer(UUID bedrockId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection connection = pool.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM `LinkedPlayers` WHERE `bedrockId` = ?"
                );
                query.setBytes(1, UUIDtoBytes(bedrockId));
                ResultSet result = query.executeQuery();
                if (!result.next()) {
                    return null;
                }

                String javaUsername = result.getString("javaUsername");
                UUID javaUniqueId = BytesToUuid(result.getBytes("javaUniqueId"));
                return LinkedPlayer.of(javaUsername, javaUniqueId, bedrockId);
            } catch (SQLException | NullPointerException exception) {
                getLogger().error("Error while getting LinkedPlayer", exception);
                throw new CompletionException("Error while getting LinkedPlayer", exception);
            }
        }, getExecutorService());
    }

    @Override
    public CompletableFuture<Boolean> isLinkedPlayer(UUID playerId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection connection = pool.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM `LinkedPlayers` WHERE `bedrockId` = ? OR `javaUniqueId` = ?"
                );
                byte[] uuidBytes = UUIDtoBytes(playerId);
                query.setBytes( 1, uuidBytes);
                query.setBytes( 2, uuidBytes);
                ResultSet result = query.executeQuery();
                return result.next();
            } catch (SQLException exception) {
                getLogger().error("Error while checking if player is a LinkedPlayer", exception);
                throw new CompletionException(
                        "Error while checking if player is a LinkedPlayer", exception
                );
            }
        }, getExecutorService());
    }

    @Override
    public CompletableFuture<Void> linkPlayer(UUID bedrockId, UUID javaId, String javaUsername) {
        return CompletableFuture.runAsync(
                () -> linkPlayer0(bedrockId, javaId, javaUsername),
                getExecutorService());
    }

    private void linkPlayer0(UUID bedrockId, UUID javaId, String javaUsername) {
        try {
            Connection connection = pool.getConnection();
            PreparedStatement query = connection.prepareStatement(
                    "INSERT INTO `LinkedPlayers` VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE " +
                            "`javaUniqueId`=VALUES(`javaUniqueId`), " +
                            "`javaUsername`=VALUES(`javaUsername`);"
            );
            query.setBytes(1, UUIDtoBytes(bedrockId));
            query.setBytes(2, UUIDtoBytes(javaId));
            query.setString(3, javaUsername);
            query.executeUpdate();
        } catch (SQLException | NullPointerException exception) {
            getLogger().error("Error while linking player", exception);
            throw new CompletionException("Error while linking player", exception);
        }
    }

    @Override
    public CompletableFuture<Void> unlinkPlayer(UUID javaId) {
        return CompletableFuture.runAsync(() -> {
            try {
                Connection connection = pool.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "DELETE FROM `LinkedPlayers` WHERE `javaUniqueId` = ? OR `bedrockId` = ?"
                );
                byte[] uuidBytes = UUIDtoBytes(javaId);
                query.setBytes(1, uuidBytes);
                query.setBytes(2, uuidBytes);
                query.executeUpdate();
            } catch (SQLException | NullPointerException exception) {
                getLogger().error("Error while unlinking player", exception);
                throw new CompletionException("Error while unlinking player", exception);
            }
        }, getExecutorService());
    }

    @Override
    public CompletableFuture<String> createLinkRequest(UUID javaId, String javaUsername,
                                                  String bedrockUsername) {
        return CompletableFuture.supplyAsync(() -> {
            String linkCode = createCode();

            createLinkRequest0(javaUsername, javaId, linkCode, bedrockUsername);

            return linkCode;
        }, getExecutorService());
    }

    private void createLinkRequest0(String javaUsername, UUID javaId, String linkCode, String bedrockUsername) {
        try {
            Connection connection = pool.getConnection();
            PreparedStatement query = connection.prepareStatement(
                    "INSERT INTO `LinkedPlayersRequest` VALUES (?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "`javaUniqueId`=VALUES(`javaUniqueId`), " +
                            "`linkCode`=VALUES(`linkCode`), " +
                            "`bedrockUsername`=VALUES(`bedrockUsername`), " +
                            "`requestTime`=VALUES(`requestTime`);"
            );
            query.setString(1, javaUsername);
            query.setBytes(2, UUIDtoBytes(javaId));
            query.setString(3, linkCode);
            query.setString(4, bedrockUsername);
            query.setLong(5, Instant.now().getEpochSecond());
            query.executeUpdate();
        } catch (SQLException | NullPointerException exception) {
            getLogger().error("Error while linking player", exception);
            throw new CompletionException("Error while linking player", exception);
        }
    }

    private void removeLinkRequest(String javaUsername) {
        try {
            Connection connection = pool.getConnection();
            PreparedStatement query = connection.prepareStatement(
                    "DELETE FROM `LinkedPlayersRequest` WHERE `javaUsername` = ?"
            );
            query.setString(1, javaUsername);
            query.executeUpdate();
        } catch (SQLException | NullPointerException exception ) {
            getLogger().error("Error while cleaning up LinkRequest", exception);
        }
    }

    @Override
    public CompletableFuture<LinkRequestResult> verifyLinkRequest(UUID bedrockId,
                                                                  String javaUsername,
                                                                  String bedrockUsername,
                                                                  String code) {
        return CompletableFuture.supplyAsync(() -> {
            LinkRequest request = getLinkRequest0(javaUsername);

            if (request == null || !isRequestedPlayer(request, bedrockId)) {
                return LinkRequestResult.NO_LINK_REQUESTED;
            }

            if (!request.getLinkCode().equals(code)) {
                return LinkRequestResult.INVALID_CODE;
            }

            // link request can be removed. Doesn't matter if the request is expired or not
            removeLinkRequest(javaUsername);

            if (request.isExpired(getVerifyLinkTimeout())) {
                return LinkRequestResult.REQUEST_EXPIRED;
            }

            linkPlayer0(bedrockId, request.getJavaUniqueId(), javaUsername);
            return LinkRequestResult.LINK_COMPLETED;
        }, getExecutorService());
    }

    private LinkRequest getLinkRequest0(String javaUsername) {
        try {
            Connection connection = pool.getConnection();

            PreparedStatement query = connection.prepareStatement(
                    "SELECT * FROM `LinkedPlayersRequest` WHERE `javaUsername` = ?"
            );
            query.setString(1, javaUsername);
            ResultSet result = query.executeQuery();

            if (result.next()) {
                UUID javaId = BytesToUuid(result.getBytes(2));
                String linkCode = result.getString(3);
                String bedrockUsername = result.getString(4);
                long requestTime = result.getLong(5);
                return new LinkRequestImpl(javaUsername, javaId, linkCode, bedrockUsername, requestTime);
            }
        } catch (SQLException | NullPointerException exception) {
            getLogger().error("Error while getLinkRequest", exception);
            throw new CompletionException("Error while getLinkRequest", exception);
        }
        return null;
    }

    public void cleanLinkRequests() {
        try {
            Connection connection = pool.getConnection();
            PreparedStatement query = connection.prepareStatement(
                    "DELETE FROM `LinkedPlayersRequest` WHERE `requestTime` < ?"
            );
            query.setLong(1, Instant.now().getEpochSecond() - getVerifyLinkTimeout());
            query.executeUpdate();
        } catch (SQLException | NullPointerException exception) {
            getLogger().error("Error while cleaning up link requests", exception);
        }
    }

    private byte[] UUIDtoBytes(UUID uuid) {
        byte[] uuidBytes = new byte[16];
        ByteBuffer.wrap(uuidBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        return uuidBytes;
    }

    private UUID BytesToUuid(byte[] uuidBytes) {
        ByteBuffer buf = ByteBuffer.wrap(uuidBytes);
        return new UUID(buf.getLong(), buf.getLong());
    }

}
