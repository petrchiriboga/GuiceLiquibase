package pl.coffeepower.guiceliquibase;

import com.google.inject.AbstractModule;
import com.google.inject.Key;

import pl.coffeepower.guiceliquibase.annotation.LiquibaseConfig;
import pl.coffeepower.guiceliquibase.annotation.LiquibaseDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Objects;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.sql.DataSource;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.configuration.GlobalConfiguration;
import liquibase.configuration.LiquibaseConfiguration;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.util.LiquibaseUtil;

public final class GuiceLiquibaseModule extends AbstractModule {

    protected void configure() {
        requireBinding(Key.get(DataSource.class, LiquibaseDataSource.class));
        requireBinding(Key.get(GuiceLiquibaseConfig.class, LiquibaseConfig.class));
        bind(GuiceLiquibase.class).asEagerSingleton();
        requestInjection(this);
    }

    @Inject
    private void executeGuiceLiquibase(GuiceLiquibase guiceLiquibase) {
        try {
            Objects.requireNonNull(guiceLiquibase, "GuiceLiquibase instance cannot be null").executeUpdate();
        } catch (LiquibaseException e) {
            throw new UnexpectedLiquibaseException(e);
        }
    }

    private static final class GuiceLiquibase {

        private static final Logger LOGGER = Logger.getLogger(GuiceLiquibase.class.getName());
        private static volatile boolean INITIALIZED = false;
        private static volatile boolean UPDATED = false;
        private final DataSource dataSource;
        private final GuiceLiquibaseConfig config;
        private final ClassLoaderResourceAccessor resourceAccessor =
                new ClassLoaderResourceAccessor(this.getClass().getClassLoader());

        @Inject
        private GuiceLiquibase(@LiquibaseDataSource DataSource dataSource,
                               @LiquibaseConfig GuiceLiquibaseConfig config) {
            LOGGER.info("Creating GuiceLiquibase for Liquibase " + LiquibaseUtil.getBuildVersion());
            this.dataSource = Objects.requireNonNull(dataSource, "Injected DataSource cannot be null.");
            this.config = Objects.requireNonNull(config, "Injected GuiceLiquibaseConfig cannot be null.");
        }

        @Override
        public int hashCode() {
            return Objects.hash(dataSource, config, resourceAccessor);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GuiceLiquibase that = (GuiceLiquibase) o;
            return Objects.equals(dataSource, that.dataSource) &&
                    Objects.equals(config, that.config) &&
                    Objects.equals(resourceAccessor, that.resourceAccessor);
        }

        private void executeUpdate() throws LiquibaseException {
            if (UPDATED) {
                LOGGER.warning("Liquibase update is already executed with success.");
                return;
            }
            if (!INITIALIZED) {
                LiquibaseConfiguration liquibaseConfiguration = LiquibaseConfiguration.getInstance();
                if (!liquibaseConfiguration.getConfiguration(GlobalConfiguration.class).getShouldRun()) {
                    String shouldRunValue = liquibaseConfiguration
                            .describeValueLookupLogic(GlobalConfiguration.class, GlobalConfiguration.SHOULD_RUN);
                    LOGGER.warning("Cannot run Liquibase updates because " + shouldRunValue + " is set to false");
                    return;
                }
                INITIALIZED = true;
                Connection connection = null;
                Database database = null;
                try {
                    connection = dataSource.getConnection();
                    JdbcConnection jdbcConnection = new JdbcConnection(Objects.requireNonNull(connection,
                            "DataSource returns null connection instance."));
                    database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(jdbcConnection);
                    Liquibase liquibase = new Liquibase(config.getChangeLogPath(), resourceAccessor, database);
                    liquibase.update(new Contexts(Collections.emptyList()),
                            new LabelExpression(Collections.emptyList()));
                    UPDATED = true;
                } catch (SQLException e) {
                    LOGGER.severe("Problem while SQL and JDBC calls.");
                    throw new DatabaseException(e);
                } catch (LiquibaseException e) {
                    LOGGER.severe("Problem while Liquibase calls.");
                    throw e;
                } finally {
                    if (database != null) {
                        database.close();
                    } else if (connection != null) {
                        try {
                            connection.rollback();
                            connection.close();
                        } catch (SQLException e) {
                            LOGGER.severe("Problem while rollback and closing Connection.");
                        }
                    }
                }
            } else {
                LOGGER.warning("GuiceLiquibase has been INITIALIZED and executed.");
            }
        }
    }
}
