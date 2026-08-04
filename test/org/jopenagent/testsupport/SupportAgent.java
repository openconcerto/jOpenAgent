package org.jopenagent.testsupport;

import java.util.List;

import org.jopenagent.core.Agent;
import org.jopenagent.core.AgentConfig;
import org.jopenagent.core.Doc;
import org.jopenagent.core.Generative;
import org.jopenagent.core.Hidden;
import org.jopenagent.core.SystemPrompt;
import org.jopenagent.core.strategy.StrategyKind;
import org.jopenagent.llm.media.Image;

/** Fixture agent used across the test suite: exercises records, scalars, lists, tools, and strategies. */
@SystemPrompt("You are a support triage agent for {self.teamName}.")
public class SupportAgent extends Agent {

    public String teamName = "Support";

    @Hidden
    public String secretApiKey = "should-not-be-visible";

    public SupportAgent(AgentConfig config) {
        super(config);
    }

    @Doc("Returns how many tickets are currently in the backlog.")
    public int ticketBacklog() {
        return 42;
    }

    public int ticketBacklogTimesTwo() {
        return ticketBacklog() * 2;
    }

    /** Static methods are not agent capabilities (they can't call generate(), and aren't instance state) and must never appear in describe()/tools. */
    public static String staticHelperNotAnAgentCapability() {
        return "static";
    }

    @Generative("Classify the support ticket into a category and give a short rationale.")
    public TicketClassification classify(String ticketText) {
        return generate(ticketText);
    }

    @Generative("Estimate the priority of this ticket from 1 (low) to 5 (high).")
    public int priority(String ticketText) {
        return generate(ticketText);
    }

    @Generative("List the distinct topics mentioned in the ticket.")
    public List<String> topics(String ticketText) {
        return generate(ticketText);
    }

    @Generative(value = "Classify the ticket using whichever tools you need.", strategy = StrategyKind.TOOL_CALLING)
    public TicketClassification classifyWithTools(String ticketText) {
        return generate(ticketText);
    }

    @Generative(value = "Compute double the current ticket backlog by writing code that calls self.ticketBacklog().", strategy = StrategyKind.CODE_ACT)
    public int doubleBacklogViaCode() {
        return generate();
    }

    @Generative("Classify the ticket shown in the attached screenshot.")
    public TicketClassification classifyFromScreenshot(Image screenshot) {
        return generate(screenshot);
    }

    @Generative(value = "Classify the ticket, being extremely careful about the category.", strategy = StrategyKind.REFLEXION)
    public TicketClassification classifyWithReflexion(String ticketText) {
        return generate(ticketText);
    }
}
