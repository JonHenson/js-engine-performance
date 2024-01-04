package jon.test.js;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * Code for testing "lazy binding" - only binding values into a JavaScript
 * context if they'll be referenced in an evaluation. The reason for this is
 * that binding in a Graal polyglot Context is very slow compared to Rhino.
 * 
 * 
 */
public class LazyBinding {
	static {
		// Use interpreted mode
		System.setProperty("polyglotimpl.DisableClassPathIsolation", "true");
		System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
	}

	/**
	 * Encapsulate a JavaScript engine and context. This allows common code to
	 * evaluate JavaScript snippets using both Rhino and Graal (any others...?)
	 */
	private interface Evaluator extends AutoCloseable {
		@Override
		void close();

		Object eval(String snippet);

		void bind(String name, Object value);
	}

	/**
	 * For {@link Context#eval(Source)} we need to create a Source object, this
	 * creates a factory that creates a Source instanced for every snippet.
	 * 
	 * @return
	 */
	private static final Function<String, Source> nonCachingSourceFactory() {
		return snippet -> Source.create("js", snippet);
	}

	/**
	 * For {@link Context#eval(Source)} we need to create a Source object, this
	 * creates a factory that will return the same Source instance every time a
	 * given snippet is seen.
	 * 
	 * @return
	 */
	private static final Function<String, Source> cachingSourceFactory() {
		Map<String, Source> sources = new HashMap<>();
		return snippet -> sources.computeIfAbsent(snippet, s -> {
			System.out.println("Caching: " + s);
			return Source.create("js", s);
		});
	}

	/**
	 * return a factory to create an Evaluator that uses graaljs. Since creating a
	 * Context is slow, Evaluators created by this factory all use the same
	 * underlying context (OK as we're just going to be using a single thread).
	 * 
	 * @param sourceFactory - convert a js snippet to a Source
	 */
	private static Supplier<Evaluator> graalEvaluatorFactory(Function<String, Source> sourceFactory) {
		Context.Builder ctxBuilder = Context.newBuilder("js").engine(Engine.create("js"));
		// Always return the same context
		Context ctx = ctxBuilder.build();
		Value bindings = ctx.getBindings("js");
		return () -> {
			return new Evaluator() {
				@Override
				public void close() {
					// Creating a context is slow, so simply clear out the bindings so it can be
					// reused.
					// NOTE: This is only OK because we're running in a single thread.
					//
					// NOTE: bindings.getMemberKeys().clear() throws an exception, as does
					// using an Iterator and calling remove().
					bindings.getMemberKeys().forEach(s -> bindings.removeMember(s));
				}

				@Override
				public Object eval(String snippet) {
					return ctx.eval(sourceFactory.apply(snippet)).as(Object.class);
				}

				@Override
				public void bind(String name, Object value) {
					bindings.putMember(name, value);
				}
			};
		};
	}

	/**
	 * Return a factory to create Evaluators that use a Rhino script engine.
	 */
	private static Supplier<Evaluator> rhinoEvaluatorFactory() {
		ScriptEngine engine = new ScriptEngineManager().getEngineByName("rhino");
		engine.getBindings(ScriptContext.ENGINE_SCOPE).put("org.mozilla.javascript.optimization_level", "-1");
		return () -> {
			ScriptContext ctx = new SimpleScriptContext();
			ctx.setBindings(engine.createBindings(), ScriptContext.ENGINE_SCOPE);

			return new Evaluator() {
				@Override
				public void close() {
					// NO-OP
				}

				@Override
				public Object eval(String snippet) {
					try {
						return engine.eval(snippet, ctx);
					} catch (ScriptException e) {
						throw new RuntimeException(e);
					}
				}

				@Override
				public void bind(String name, Object value) {
					ctx.getBindings(ScriptContext.ENGINE_SCOPE).put(name, value);
				}

			};
		};
	}

	/**
	 * Naive unique token extraction (assumes whitespace around them and doesn't
	 * take account of parenthesis or operators). e.g.
	 * 
	 * <pre>
	 * (FOO_1+FOO_2)  - FOO_1 and FOO_2 not extracted correctly ["(FOO_1+FOO_2)"]
	 * (FOO_1 + FOO_2) - FOO_1 and FOO_2 not extracted correctly ["(FOO_1", "+", "FOO_2)"]
	 * ( FOO_1 + FOO_ 2 ) - FOO_1 and FOO_2 extracted ["(", "FOO_1", "+", "FOO_2", ")" ]
	 * </pre>
	 * 
	 * @param snippet
	 * @return
	 */
	private static Stream<String> tokensFromSnippet(String snippet) {
		return Arrays.stream(snippet.split(" ")).distinct();
	}

	/**
	 * Iterates over some snippets of code, evaluating each one
	 * <code>iterations</code> times. Each evaluation creates a new
	 * <code>Evaluator</code> using the given factory and binds variables to it
	 * using the given <code>binder</code>.
	 * 
	 * @param evaluatorFactory
	 * @param binder
	 * @param iterations
	 * @return time taken to execute the iterations
	 */
	private static Duration test(Supplier<Evaluator> evaluatorFactory, BiConsumer<Evaluator, String> binder,
			int iterations) {
		// NOTE: formatting of each snippet is such that the naive token extraction will
		// work
		// [see: tokensFromSnippet(String snippet)]
		String[] snippets = { //
				"FOO_10 === FOO_1 + FOO_2 + FOO_3 + FOO_4", //
				"FOO_8 === ( FOO_1 + FOO_1 + FOO_2 ) * FOO_2", //
				"if( FOO_48 + FOO_1 == 49.0 ) { ++ FOO_1 === 2 } else { false }", //
				"( FOO_1 ++ === 1 && ++ FOO_1 === 3 )", //
				"FOO_58 === 'foo 58'", //
				"FOO_58 + ' ' + FOO_59 === 'foo 58 foo 59'" //
		};

		return time(() -> {
			for (int i = 0; i < iterations; i++) {
				for (String s : snippets) {
					if (!(boolean) eval(evaluatorFactory, binder, s)) {
						System.out.println(s + " was False");
					}
				}
			}
		});
	}

	/**
	 * @param task
	 * @return duration of the task
	 */
	private static Duration time(Runnable task) {
		Instant start = Instant.now();
		try {
			task.run();
		} catch (Throwable t) {
			t.printStackTrace();
		}
		return Duration.between(start, Instant.now());

	}

	/**
	 * Creates an Evaluator; binds values into it; evaluates the code snippet;
	 * closes the evaluator
	 * 
	 * @param evaluatorFactory
	 * @param binder
	 * @param snippet
	 * @return outcome of the evaluation
	 */
	private static Object eval(Supplier<Evaluator> evaluatorFactory, BiConsumer<Evaluator, String> binder,
			String snippet) {
		try (Evaluator evaluator = evaluatorFactory.get()) {
			binder.accept(evaluator, snippet);
			return evaluator.eval(snippet);
		}
	}

	/**
	 * Generate a Map of variable names to values:
	 * 
	 * <pre>
	 * FOO_0 ... FOO_49 = 0 ... 49 
	 * FOO_50 ... FOO_99 = "foo 50" ... "foo 99"
	 * </pre>
	 */
	private static Map<String, Object> variables() {
		Map<String, Object> ret = new HashMap<>();
		for (int i = 0; i < 50; i++) {
			ret.put("FOO_" + i, Integer.valueOf(i));
		}
		for (int i = 50; i < 100; i++) {
			ret.put("FOO_" + i, "foo " + i);
		}
		return Collections.unmodifiableMap(ret);
	}

	public static void main(String[] args) {
		Map<String, Object> variables = variables();

		// Bind all variables in the Map into an Evaluator
		BiConsumer<Evaluator, String> bindAll = (evaluator, snippet) -> variables
				.forEach((name, value) -> evaluator.bind(name, value));
		// Iterate over extracted tokens and bind if they have a value in the variables
		// map
		BiConsumer<Evaluator, String> bindReferenced = (evaluator, snippet) -> {
			tokensFromSnippet(snippet).forEach(token -> {
				if (variables.containsKey(token)) {
					evaluator.bind(token, variables.get(token));
				}
			});
		};
		// Iterate over all variables in the map, filtering for ones that also appear in
		// the tokens
		// extracted from the snippet.
		BiConsumer<Evaluator, String> bindReferencedInefficient = (evaluator, snippet) -> {
			Set<String> tokens = tokensFromSnippet(snippet).collect(Collectors.toSet());
			variables.entrySet().stream().filter(e -> tokens.contains(e.getKey()))
					.forEach(e -> evaluator.bind(e.getKey(), e.getValue()));

		};

		Supplier<Evaluator> evaluatorFactory = graalEvaluatorFactory(cachingSourceFactory());
		//Supplier<Evaluator> evaluatorFactory = rhinoEvaluatorFactory();

		int totalIterations = 5_000;

		for (int i = 0; i < 5; i++) {
			System.out.println("       Bind All: " + test(evaluatorFactory, bindAll, totalIterations));
			System.out.println("Bind Referenced: " + test(evaluatorFactory, bindReferenced, totalIterations));
			System.out
					.println("    Inefficient: " + test(evaluatorFactory, bindReferencedInefficient, totalIterations));
		}
	}
}
