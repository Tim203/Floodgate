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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.geysermc.floodgate.config.FloodgateConfig;
import org.geysermc.floodgate.config.FloodgateConfig.PlayerLinkDatabase;
import org.geysermc.floodgate.link.CommonPlayerLink;
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
            statement.setQueryTimeout(30);  // set timeout to 30 sec.
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `LinkedPlayers` ( " +
                            "`bedrockId` VARCHAR(36) NOT NULL , `javaUniqueId` VARCHAR(36) NOT NULL , " +
                            "`javaUsername` VARCHAR(16) NOT NULL ," +
                            " PRIMARY KEY (`bedrockId`), INDEX (`bedrockId`, `javaUniqueId`)) ENGINE = InnoDB;"
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
                query.setString(1, bedrockId.toString());
                ResultSet result = query.executeQuery();
                if (!result.next()) {
                    return null;
                }

                String javaUsername = result.getString("javaUsername");
                UUID javaUniqueId = UUID.fromString(result.getString("javaUniqueId"));
                return LinkedPlayer.of(javaUsername, javaUniqueId, bedrockId);
            } catch (SQLException | NullPointerException exception) {
                getLogger().error("Error while getting LinkedPlayer", exception);
                throw new CompletionException("Error while getting LinkedPlayer", exception);
            }
        }, getExecutorService());
    }

    @Override
    public CompletableFuture<Boolean> isLinkedPlayer(UUID bedrockId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Connection connection = pool.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT * FROM `LinkedPlayers` WHERE `bedrockId` = ? OR `javaUniqueId` = ?"
                );
                query.setString(1, bedrockId.toString());
                query.setString(2, bedrockId.toString());
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
    public CompletableFuture<Void> linkPlayer(UUID bedrockId, UUID javaId, String username) {
        return CompletableFuture.runAsync(() -> {
            try {
                Connection connection = pool.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "INSERT INTO `LinkedPlayers` VALUES (?, ?, ?)"
                );
                query.setString(1, bedrockId.toString());
                query.setString(2, javaId.toString());
                query.setString(3, username);
                query.executeUpdate();
            } catch (SQLException | NullPointerException exception) {
                getLogger().error("Error while linking player", exception);
                throw new CompletionException("Error while linking player", exception);
            }
        }, getExecutorService());
    }

    @Override
    public CompletableFuture<Void> unlinkPlayer(UUID javaId) {
        return CompletableFuture.runAsync(() -> {
            try {
                Connection connection = pool.getConnection();
                PreparedStatement query = connection.prepareStatement(
                        "DELETE FROM `LinkedPlayers` WHERE `javaUniqueId` = ? OR `bedrockId` = ?"
                );
                query.setString(1, javaId.toString());
                query.setString(2, javaId.toString());
                query.executeUpdate();
            } catch (SQLException | NullPointerException exception) {
                getLogger().error("Error while unlinking player", exception);
                throw new CompletionException("Error while unlinking player", exception);
            }
        }, getExecutorService());
    }
}
