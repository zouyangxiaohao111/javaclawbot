package config.channel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

// ✅ Python ChannelsConfig includes matrix: MatrixConfig
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MatrixConfig {
    private boolean enabled = false;
    private String homeserver = "https://matrix.org";
    private String accessToken = "";
    private String userId = "";          // e.g. @bot:matrix.org
    private String deviceId = "";
    private boolean e2eeEnabled = true;
    private int syncStopGraceSeconds = 2;
    private int maxMediaBytes = 20 * 1024 * 1024;
    private List<String> allowFrom = new ArrayList<>();
    private String groupPolicy = "open";        // open / mention / allowlist
    private List<String> groupAllowFrom = new ArrayList<>();
    private boolean allowRoomMentions = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHomeserver() {
        return homeserver;
    }

    public void setHomeserver(String homeserver) {
        this.homeserver = homeserver;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public boolean isE2eeEnabled() {
        return e2eeEnabled;
    }

    public void setE2eeEnabled(boolean e2eeEnabled) {
        this.e2eeEnabled = e2eeEnabled;
    }

    public int getSyncStopGraceSeconds() {
        return syncStopGraceSeconds;
    }

    public void setSyncStopGraceSeconds(int syncStopGraceSeconds) {
        this.syncStopGraceSeconds = syncStopGraceSeconds;
    }

    public int getMaxMediaBytes() {
        return maxMediaBytes;
    }

    public void setMaxMediaBytes(int maxMediaBytes) {
        this.maxMediaBytes = maxMediaBytes;
    }

    public List<String> getAllowFrom() {
        return allowFrom;
    }

    public void setAllowFrom(List<String> allowFrom) {
        this.allowFrom = (allowFrom != null) ? allowFrom : new ArrayList<>();
    }

    public String getGroupPolicy() {
        return groupPolicy;
    }

    public void setGroupPolicy(String groupPolicy) {
        this.groupPolicy = groupPolicy;
    }

    public List<String> getGroupAllowFrom() {
        return groupAllowFrom;
    }

    public void setGroupAllowFrom(List<String> groupAllowFrom) {
        this.groupAllowFrom = (groupAllowFrom != null) ? groupAllowFrom : new ArrayList<>();
    }

    public boolean isAllowRoomMentions() {
        return allowRoomMentions;
    }

    public void setAllowRoomMentions(boolean allowRoomMentions) {
        this.allowRoomMentions = allowRoomMentions;
    }
}