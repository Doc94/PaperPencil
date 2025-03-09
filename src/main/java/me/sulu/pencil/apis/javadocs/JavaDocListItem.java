package me.sulu.pencil.apis.javadocs;

public record JavaDocListItem(
  String url,
  String type,
  String packagePath,
  String name
) {

  public String getNameSuggest() {
    return "%s (%s)".formatted(this.name, this.type);
  }

}
