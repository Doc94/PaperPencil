package me.sulu.pencil.commands;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import discord4j.core.event.domain.interaction.ChatInputAutoCompleteEvent;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.component.ActionRow;
import discord4j.core.object.component.Button;
import discord4j.core.object.component.Container;
import discord4j.core.object.component.TextDisplay;
import discord4j.core.object.component.TopLevelMessageComponent;
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec;
import discord4j.discordjson.json.ApplicationCommandOptionChoiceData;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import discord4j.discordjson.json.ImmutableApplicationCommandRequest;
import discord4j.rest.util.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.sulu.pencil.Pencil;
import me.sulu.pencil.apis.javadocs.JavaDocListItem;
import me.sulu.pencil.apis.javadocs.JavaDocSearch;
import me.sulu.pencil.apis.javadocs.JavaDocSites;
import me.sulu.pencil.util.StringUtil;
import reactor.core.publisher.Mono;

public class JavaDocs extends Command {

  private final ApplicationCommandRequest request;
  private final JavaDocSearch search;
  private final Cache<String, JavaDocListItem> cache = Caffeine.newBuilder()
    .expireAfterWrite(Duration.ofMinutes(10))
    .build();

  public JavaDocs(Pencil pencil) {
    super(pencil);
    this.search = new JavaDocSearch(pencil);
    ImmutableApplicationCommandRequest.Builder builder = ImmutableApplicationCommandRequest.builder();
    for (JavaDocSites javaDocSite : JavaDocSites.values()) {
      builder
        .name("javadocs")
        .description("Search the PaperMC javadocs")
        .addOption(ApplicationCommandOptionData.builder()
          .type(ApplicationCommandOption.Type.SUB_COMMAND.getValue())
          .description("Search in the javadocs of " + javaDocSite.getProject() + " project")
          .name(javaDocSite.getProject())
          .addOption(ApplicationCommandOptionData.builder()
            .name("term")
            .description("Search term")
            .type(ApplicationCommandOption.Type.STRING.getValue())
            .autocomplete(true)
            .required(true)
            .build())
          .build());
    }
    this.request = builder.build();
  }

  @Override
  public boolean global() {
    return true;
  }

  @Override
  public ApplicationCommandRequest request() {
    return this.request;
  }

  @Override
  public Mono<Void> execute(ChatInputInteractionEvent event) {
    final String term = event.getOptions().stream().findFirst().map(subCommand -> subCommand.getOption("term").orElseThrow().getValue().orElseThrow().asString()).orElseThrow();
    final JavaDocSites javaDocSite = event.getOptions().stream().findFirst().map(ApplicationCommandInteractionOption::getName).map(String::toUpperCase).map(JavaDocSites::valueOf).orElseThrow();
    JavaDocListItem item = cache.getIfPresent(term);

    System.out.println("WORKING WITH " + term);

    if (item == null) {
      System.out.println("NULLED");
      return search.search(javaDocSite, term).sampleFirst(jdItem -> this.handleJDListItem(event, jdItem)).then();
    }

    return this.handleJDListItem(event, item);
  }

  @Override
  public Mono<Void> complete(ChatInputAutoCompleteEvent event) {
    final String term = event.getFocusedOption().getValue().orElseThrow().asString();
    if (term.isBlank()) {
      return event.respondWithSuggestions(Collections.emptyList());
    }

    final JavaDocSites javaDocSite = event.getOptions().stream().findFirst().map(ApplicationCommandInteractionOption::getName).map(String::toUpperCase).map(JavaDocSites::valueOf).orElse(null);

    if (javaDocSite == null) {
      return event.respondWithSuggestions(Collections.emptyList());
    }

    return this.search.search(javaDocSite, term)
      .doOnNext(next -> cache.put(String.valueOf(next.hashCode()), next)) // this is awful, but also...
      .map(item -> ApplicationCommandOptionChoiceData.builder()
        .name(StringUtil.left(item.getNameSuggest(), 100))
        .value(String.valueOf(item.hashCode()))
        .build())
      .cast(ApplicationCommandOptionChoiceData.class)
      .take(25)
      .collectList()
      .flatMap(event::respondWithSuggestions);
  }

  public Mono<Void> handleJDListItem(ChatInputInteractionEvent event, JavaDocListItem baseItem) {
    return this.search.fetchJavaDocItem(baseItem).flatMap(item -> {
      InteractionApplicationCommandCallbackSpec.Builder builder = InteractionApplicationCommandCallbackSpec.builder();
      List<TopLevelMessageComponent> topComponents = new ArrayList<>();
      List<TextDisplay> containerComponents = new ArrayList<>();

      containerComponents.add(TextDisplay.of("**Package** " + item.packagePath()));
      containerComponents.add(TextDisplay.of("### " + item.type() + " - " + item.name()));

      if (!item.description().isBlank()) {
        containerComponents.add(TextDisplay.of(item.description()));
      }

      topComponents.add(Container.of(containerComponents));
      if (item.deprecated()) {
        List<TextDisplay> jdDeprecatedContainerComponents = new ArrayList<>();
        jdDeprecatedContainerComponents.add(TextDisplay.of("Deprecated"));
        if (!item.deprecatedMessage().isBlank()) {
          jdDeprecatedContainerComponents.add(TextDisplay.of("```" + item.deprecatedMessage() + "```"));
        }
        Container jdDeprecatedContainer = Container.of(Color.RED, jdDeprecatedContainerComponents);
        topComponents.add(jdDeprecatedContainer);
      }
      topComponents.add(ActionRow.of(Button.link(item.url(), "Go")));
      builder.addAllComponents(topComponents);
      return event.reply(builder.build());
    });
  }
}
