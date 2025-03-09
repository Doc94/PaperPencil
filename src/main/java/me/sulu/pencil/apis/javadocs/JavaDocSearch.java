package me.sulu.pencil.apis.javadocs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import me.sulu.pencil.Pencil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class JavaDocSearch {

  private final Pencil pencil;
  private final Cache<String, String> cacheProjectVersion;
  private final Cache<JavaDocSites, Set<JavaDocListItem>> cacheJavaDocs;

  public JavaDocSearch(final Pencil pencil) {
    this.pencil = pencil;
    this.cacheProjectVersion = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(60))
      .build();
    this.cacheJavaDocs = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(60))
      .build();
    for (JavaDocSites site : JavaDocSites.values()) {
      if (site == JavaDocSites.FOLIA) {
       continue;
      }
      this.search(site, "").collect(Collectors.toSet()).doOnSuccess(javaDocItems -> cacheJavaDocs.put(site, javaDocItems)).subscribe();
    }
  }

  private void cacheAddJavaDoc(JavaDocSites javaDocSite, JavaDocListItem javaDocListItem) {
    Set<JavaDocListItem> items = this.cacheJavaDocs.getIfPresent(javaDocSite);
    if (items == null) {
      items = new HashSet<>();
    }
    items.add(javaDocListItem);
    this.cacheJavaDocs.put(javaDocSite, items);
  }

  public Flux<JavaDocListItem> search(JavaDocSites javaDocSite, String query) {
    Set<JavaDocListItem> items = this.cacheJavaDocs.getIfPresent(javaDocSite);
    if (items != null && !items.isEmpty()) {
      return Flux.fromIterable(items).filter(javaDocListItem -> javaDocListItem.name().toLowerCase().contains(query.toLowerCase()));
    }
    return this.getLastProjectVersion(javaDocSite).flatMapMany(version -> {
      final String baseUrl = javaDocSite.getUrl(version);
      final String allClassesUrl = baseUrl + "allclasses-index.html";
      return this.fetchDocument(allClassesUrl)
        .flatMapMany(doc -> this.extractLinks(doc)
          .filter(itemElement -> itemElement.text().toLowerCase().contains(query.toLowerCase()))
          .flatMap(indexItemElement -> {
            final String indexHref = indexItemElement.attr("href");
            final String urlDocs = (indexHref.startsWith("https") ? indexHref : baseUrl + indexHref);
            final String jdElementName = indexItemElement.text();
            final String jdElementType = indexItemElement.attr("title").split(" ")[0];

            Pattern pattern = Pattern.compile("^(?:https?://[^/]+/)?(?:[^/]+/\\d+(?:\\.\\d+)*/)?([^/]+(?:/[^/]+)*)/[^/]+\\.html$");
            Matcher matcher = pattern.matcher(indexHref);
            final String jdElementPackage = (matcher.find()) ? matcher.group(1).replaceAll("/", ".") : "---";
            JavaDocListItem javaDocListItem = new JavaDocListItem(urlDocs, jdElementType, jdElementPackage, jdElementName);
            this.cacheAddJavaDoc(javaDocSite, javaDocListItem);
            return Mono.just(javaDocListItem);
          })
        );
    });
  }

  public Mono<JavaDocItem> fetchJavaDocItem(JavaDocListItem javaDocListItem) {
    return this.fetchDocument(javaDocListItem.url()).map(documentElement -> {
      Element documentClassDescElement = documentElement.selectFirst("#class-description");
      final String jdElementDescription = Optional.ofNullable(documentElement.selectFirst(".block")).map(Element::wholeText).orElse("");
      final boolean jdElementDeprecated = documentClassDescElement != null && documentClassDescElement.selectFirst(".deprecated-label") != null;
      String jdElementDeprecatedMessage = "";
      if (jdElementDeprecated) {
        Element docClassDeprecatedMessage = documentClassDescElement.selectFirst(".deprecation-comment");
        if (docClassDeprecatedMessage != null) {
          jdElementDeprecatedMessage = docClassDeprecatedMessage.wholeText();
        }
      }
      return new JavaDocItem(javaDocListItem.url(), javaDocListItem.type(), javaDocListItem.packagePath(), javaDocListItem.name(), jdElementDescription, jdElementDeprecated, jdElementDeprecatedMessage);
    });
  }

  private Mono<Document> fetchDocument(String url) {
    return Mono.fromCallable(() -> {
      try {
        return Jsoup.connect(url).followRedirects(true).get();
      } catch (IOException e) {
        throw new RuntimeException("Cannot get the document", e);
      }
    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  private Flux<Element> extractLinks(Document doc) {
    return Flux.fromIterable(doc.select("a[href][title]"));
  }

  private Mono<String> getLastProjectVersion(JavaDocSites javaDocSite) {
    String version = this.cacheProjectVersion.getIfPresent(javaDocSite.getProject());
    if (version != null) return Mono.just(version);

    return this.pencil.http().get()
      .uri("https://api.papermc.io/v2/projects/" + javaDocSite.getProject())
      .responseContent()
      .aggregate()
      .asString()
      .map(response -> {
        try {
          JsonNode node = this.pencil.jsonMapper().readTree(response.replace(")]}'\n", ""));

          return StreamSupport
            .stream(node.get("versions").spliterator(), false)
            .map(JsonNode::asText)
            .toList().getLast().replace("-SNAPSHOT", "");
        } catch (JsonProcessingException e) {
          throw new RuntimeException(e);
        }
      })
      .doOnNext(v -> this.cacheProjectVersion.put(javaDocSite.getProject(), v));
  }

}
