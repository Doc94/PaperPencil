package me.sulu.pencil.apis.javadocs;

public record JavaDocItem(
  String url,
  String type,
  String packagePath,
  String name,
  String description,
  boolean deprecated,
  String deprecatedMessage
) {
}
