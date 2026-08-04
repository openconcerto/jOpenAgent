<div align="center">

# jOpenAgent

**The agent harness for Java.**

[![Maven Central](https://img.shields.io/maven-central/v/org.jopenagent/jopenagent.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/org.jopenagent/jopenagent)
[![CI](https://github.com/openconcerto/jOpenAgent/actions/workflows/ci.yml/badge.svg)](https://github.com/openconcerto/jOpenAgent/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://adoptium.net/)
[![Dependencies](https://img.shields.io/badge/dependencies-0-brightgreen.svg)](#zero-dependencies-is-a-commitment-not-an-accident)

[Website](https://www.jopenagent.org/) ·
[Quickstart](https://www.jopenagent.org/quickstart.html) ·
[Examples](https://www.jopenagent.org/examples.html) ·
[Docs](https://www.jopenagent.org/docs.html) ·


</div>

---

jOpenAgent is a full **harness** around a model: typed input and output, code-as-action, pluggable reasoning strategies,
durable object state, memory, skills, MCP, tracing and evaluation — written as ordinary
Java classes that your debugger, your test runner and your IDE already understand.

An agent is a plain Java class. Fields are state, ordinary methods are deterministic
capabilities, and methods annotated `@Generative` are delegated to an LLM at runtime.

```java
@SystemPrompt("You analyze customer feedback.")
public class FeedbackAgent extends Agent {

    public FeedbackAgent(AgentConfig config) {
        super(config);
    }

    @Generative("Extract a rating out of 5, an overall sentiment word, and up to 3 highlights.")
    public ReviewSummary summarize(String review) {
        return generate(review);   // the interception point
    }

    public record ReviewSummary(int rating, String sentiment, List<String> highlights) { }
}
```

```java
FeedbackAgent agent = new FeedbackAgent(AgentConfig.builder().llmClient(llmClient).build());
ReviewSummary summary = agent.summarize("Great product, but shipping was slow");
System.out.println(summary.rating() + " / " + summary.sentiment());
```

No dynamic proxy, no bytecode generation, no annotation processor. The method body is real,
the stack frames are real, and `generate(...)` is where it hands off to the harness. Change
the `@Generative` text and the behaviour changes — the instruction string *is* the prompt.

## Install

```xml
<dependency>
    <groupId>org.jopenagent</groupId>
    <artifactId>jopenagent</artifactId>
    <version>1.0.0</version>
</dependency>
```

```groovy
implementation 'org.jopenagent:jopenagent:1.0.0'
```

```kotlin
implementation("org.jopenagent:jopenagent:1.0.0")
```

Or no build tool at all — the jar *is* the whole installation, because there is nothing
behind it to resolve:

```bash
javac -parameters -cp jopenagent-1.0.0.jar MyAgent.java
java --add-modules jdk.jshell -cp .:jopenagent-1.0.0.jar MyAgent
```

**Requirements:** JDK 21 or newer, compiled with `-parameters` (so parameter names reach
the prompts and the JSON schemas), and `--add-modules jdk.jshell` at runtime *only* if you
use the `CODE_ACT` strategy. A model: an Anthropic or OpenAI key, or a local LM Studio /
Ollama server, which needs neither.

## Why the harness, and not just a client

Java has had good libraries for *talking to* language models for years : chat clients,
embeddings, vector stores, retrieval. That is a real category, and it is not this one.

A harness is the architecture *around* the model: how state is held, how outputs are typed and repaired, how the model acts, how context is disclosed, how the loop is written, how the whole thing is traced and measured. Published research on agent architecture is consistent that the harness accounts for double-digit swings in benchmark results on an unchanged model. Picking a better model is the small lever.



## What's in the box

**Core mechanism**

- **Agents are Java objects.** Fields are state, ordinary methods are capabilities,
  `@SystemPrompt`/`@Generative` values carry the prompts, method signatures are typed contracts.
- **`@Generative` methods are LLM-driven** via a `StackWalker` trampoline: the method has a real body ending in `return generate(...)`, which identifies the calling method, reads its instruction and declared return type by reflection, and dispatches to the configured strategy.
- **Strict typing with repair.** `ObjectBinder` binds and validates LLM JSON against records, POJOs, `List`/`Set`/`Map`, `Optional`, enums and primitives with no per-class registration.
  Invalid output triggers a bounded retry with the validation error fed back to the model.
- **Progressive disclosure.** `Agent#describe()` (and `{doc(self)}` in a prompt template) renders an agent's visible fields and methods; `@Hidden`/`@Shown` control visibility.
- **Context blocks.** `self.context` holds static (`put`) and dynamic (`setDynamic`,
  re-evaluated on every prompt build) blocks, rendered into the system prompt automatically.

**Four reasoning strategies**, selectable per method via `@Generative(strategy = ...)`

- `PREDICT` (default) : one structured-output call, with automatic repair.
- `CODE_ACT` : the model writes Java, executed by a JShell-backed sandbox with a live `self` bound to the agent, iterating until it calls `return_result`. In-process by default, `SandboxMode.OUT_OF_PROCESS` runs it in a child JVM so a timeout is a real process kill.
- `TOOL_CALLING` : a classic function-calling loop over the agent's own ordinary methods (and any attached MCP server's tools), exposed as JSON-schema tools.
- `REFLEXION` : generate → structured self-critique → retry with feedback.

**State, tools and knowledge**

- **Cross-session memory** — `self.memory`: `remember`/`recall`/`update`/`forget`, with similarity dedup-on-write and importance/recency-weighted recall. Default is an in-memory store plus a deterministic **offline** `HashingEmbedder` (no network); swap in JsonFileMemoryStore` for persistence and `OpenAiEmbedder` for real embeddings hosted, or local via LM Studio / Ollama...)
- **Skills** — `self.skills`: attach a `SKILL.md` bundle (frontmatter + markdown, sandboxed `readFile`, and `runScript(...)` launching a real subprocess with a hard-kill timeout).
- **MCP** — a real stdio JSON-RPC 2.0 client, attached as a plain field. Its tools are
  callable straight from `CODE_ACT` code and auto-merged into `TOOL_CALLING`'s schema list.
  Verified end-to-end against the official `@modelcontextprotocol/server-everything`.
- **Persistent history + auto-summarisation** — `self.history` accumulates across calls and collapses the oldest entries into an LLM-produced summary past a configurable threshold.
- **Multimodal** — an `Image`-typed `@Generative` parameter is attached to the request as real image content, not dumped into the text prompt. Wired through both LLM clients.

**Observability**

- **Traced by default.** Every LLM call, code execution and generation method is a `Span`with explicit parent/child nesting.

- **Four exporters** : plain JSON, **ATIF v1.7**, **OTLP/HTTP** (any collector), and **Langfuse**.
- **Three viewers** : an embedded web trace viewer built on `com.sun.net.httpserver` alone, a native **Swing** desktop viewer, and a terminal pretty-printer.
- **An eval harness** : define an `EvalSuite` of `EvalCase`s, run it against a `Scorer`, and read pass rates and per-model breakdowns from the viewer's Experiments tab. Results *are* trace spans; there is no second store.

**LLM clients** — `AnthropicClient` (Messages API) and `OpenAiClient` (Chat Completions: real OpenAI, local Ollama, local LM Studio), both built on `java.net.http.HttpClient`.

## Zero dependencies

Adding jOpenAgent to a project adds exactly one jar to the classpath. There is no
transitive tree to resolve, review, pin or patch, and no CVE arriving through a library you never chose. HTTP is `java.net.http`; JSON is the bundled `org.jopenkit.json`; the sandbox is `jdk.jshell`; the trace viewer's web server is `jdk.httpserver`; MCP servers are launched with `ProcessBuilder`. The JDK is enough.

The only dependency in the POM at all is JUnit 5, at `test` scope, which never reaches you.

## Build from source

```bash
git clone https://github.com/openconcerto/jOpenAgent.git
cd jOpenAgent
mvn clean verify
```



## Examples

Nineteen runnable examples live in [`src/org/jopenagent/examples`](src/org/jopenagent/examples),
a progressive tutorial from a first generation method to the eval harness. Each has a `main()`.
Configure a model through the environment:

```bash
ANTHROPIC_API_KEY=sk-ant-...   JOPENAGENT_MODEL=claude-sonnet-4-5   # Anthropic
OPENAI_API_KEY=sk-...          JOPENAGENT_MODEL=gpt-4o-mini         # OpenAI
JOPENAGENT_LMSTUDIO_MODEL=qwen/qwen3-coder-30b                      # local LM Studio, no key
JOPENAGENT_OLLAMA_MODEL=llama3.1                                    # local Ollama, no key
```

|                             |                             |                 |                            |
| --------------------------- | --------------------------- | --------------- | -------------------------- |
| E01 First generation method | E06 Tracing + JSON export   | E11 MCP tools   | E16 Trace viewer (web)     |
| E02 Structured output       | E07 Context blocks          | E12 Memory      | E17 Trace viewer (desktop) |
| E03 Tools via self          | E08 Code-as-action          | E13 Multimodal  | E18 Out-of-process sandbox |
| E04 Comparing strategies    | E09 History + summarisation | E14 ATIF export | E19 Eval harness           |
| E05 Progressive disclosure  | E10 Skills                  | E15 Reflexion   |                            |

All nineteen have been run for real against a local LM Studio server and/or a real MCP
server — not merely compiled.

## Using jOpenAgent with a coding agent

This repository ships an **AI skill** at [`skills/jopenagent/SKILL.md`](skills/jopenagent/SKILL.md):
drop it into a Claude Code / agent skills directory and your assistant will write jOpenAgent
code against the real API instead of guessing. See [jopenagent.org/skill.html](https://www.jopenagent.org/skill.html).

## ⚠️ Safety

Agents can be configured to execute LLM-generated code or to launch external MCP server subprocesses. Generated code, or a malicious MCP server, may take dangerous actions: exfiltrating data, deleting files, modifying the environment.

## Documentation

- **[jopenagent.org](https://www.jopenagent.org/)** — features, quickstart, examples, full manual



## License

Apache License 2.0 — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
