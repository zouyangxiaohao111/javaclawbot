package config.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import config.hearbet.HeartbeatConfig;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GatewayConfig {
    private String host = "0.0.0.0";
    private int port = 18790;
    private HeartbeatConfig heartbeat = new HeartbeatConfig();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public HeartbeatConfig getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(HeartbeatConfig heartbeat) {
        this.heartbeat = (heartbeat != null) ? heartbeat : new HeartbeatConfig();
    }
}