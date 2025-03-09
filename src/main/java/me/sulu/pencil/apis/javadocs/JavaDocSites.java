package me.sulu.pencil.apis.javadocs;

public enum JavaDocSites {

  PAPER("paper"),
  FOLIA("folia"),
  VELOCITY("velocity"),
  ;

  private static final String JD_URL_PLACEHOLDER = "https://jd.papermc.io/%s/%s/";

  private final String project;

  JavaDocSites(String project) {
    this.project = project;
  }

  public String getProject() {
    return project;
  }

  public String getUrl(final String version) {
    return JD_URL_PLACEHOLDER.formatted(this.project, version);
  }

}
