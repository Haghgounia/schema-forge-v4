package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard database grants applied to every generated table.
 * The configured grantee is a database role/principal, not an application user id.
 */
@ConfigurationProperties(prefix = "schemaforge.standards")
public class GrantProperties {
    private List<GrantRule> grants = new ArrayList<>(defaultGrants());

    public List<GrantRule> getGrants() {
        return grants;
    }

    public void setGrants(List<GrantRule> grants) {
        this.grants = grants == null ? new ArrayList<>() : new ArrayList<>(grants);
    }

    public static GrantProperties defaults() {
        return new GrantProperties();
    }

    private static List<GrantRule> defaultGrants() {
        return List.of(
                new GrantRule("U_DEVELOPER", List.of("SELECT", "INSERT", "UPDATE", "DELETE")),
                new GrantRule("U_DESIGNER", List.of("SELECT", "INSERT", "UPDATE", "DELETE"))
        );
    }

    public static class GrantRule {
        private String grantee;
        private List<String> privileges = new ArrayList<>();

        public GrantRule() {
        }

        public GrantRule(String grantee, List<String> privileges) {
            this.grantee = grantee;
            this.privileges = privileges == null ? new ArrayList<>() : new ArrayList<>(privileges);
        }

        public String getGrantee() {
            return grantee;
        }

        public void setGrantee(String grantee) {
            this.grantee = grantee;
        }

        public List<String> getPrivileges() {
            return privileges;
        }

        public void setPrivileges(List<String> privileges) {
            this.privileges = privileges == null ? new ArrayList<>() : new ArrayList<>(privileges);
        }
    }
}
