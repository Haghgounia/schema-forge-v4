package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "schemaforge.spell-check")
public class SpellCheckProperties {
    private boolean enabled = false;
    private String endpoint = "https://api.languagetool.org/v2/check";
    private String language = "en-US";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private int maximumSuggestions = 3;
    private boolean failOpen = true;
    private List<String> technicalTerms = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaximumSuggestions() { return maximumSuggestions; }
    public void setMaximumSuggestions(int maximumSuggestions) { this.maximumSuggestions = maximumSuggestions; }
    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    public List<String> getTechnicalTerms() { return technicalTerms; }
    public void setTechnicalTerms(List<String> technicalTerms) {
        this.technicalTerms = technicalTerms == null ? new ArrayList<>() : new ArrayList<>(technicalTerms);
    }

    public static SpellCheckProperties defaults() { return new SpellCheckProperties(); }
}
