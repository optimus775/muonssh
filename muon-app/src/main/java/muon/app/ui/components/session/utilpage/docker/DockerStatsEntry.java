package muon.app.ui.components.session.utilpage.docker;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DockerStatsEntry {
    private String name;
    private String containerId;
    private String cpuPercent;
    private String memUsage;
    private String memPercent;
    private String netIo;
    private String blockIo;
    private String pids;
}
