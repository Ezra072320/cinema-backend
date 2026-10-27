package edu.czu.cinema.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cinema.jobs")
public class JobProperties {
  public enum Mode {
    LOCAL,
    XXL
  }

  private Mode mode = Mode.LOCAL;

  public Mode getMode() {
    return mode;
  }

  public void setMode(Mode mode) {
    this.mode = mode;
  }
}
