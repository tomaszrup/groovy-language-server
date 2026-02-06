////////////////////////////////////////////////////////////////////////////////
// Copyright 2026 Tomasz Rup
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
//
// Author: Tomasz Rup
// No warranty of merchantability or fitness of any kind.
// Use this software at your own risk.
////////////////////////////////////////////////////////////////////////////////
package com.tomaszrup.groovyls.providers;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;

import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.compiler.util.GroovyASTUtils;
import com.tomaszrup.groovyls.util.SpockUtils;

/**
 * Provides Spock-specific completion items: block labels, feature method
 * snippets, common assertions, and annotation completions.
 */
public class SpockCompletionProvider {

    private ASTNodeVisitor ast;

    public SpockCompletionProvider(ASTNodeVisitor ast) {
        this.ast = ast;
    }

    /**
     * Attempts to provide Spock-specific completions for the given position.
     * Returns an empty list if the context is not inside a Spock specification.
     */
    public List<CompletionItem> provideSpockCompletions(URI uri, Position position, ASTNode offsetNode) {
        List<CompletionItem> items = new ArrayList<>();

        if (ast == null || offsetNode == null) {
            return items;
        }

        // Find enclosing class
        ASTNode enclosingClass = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, ClassNode.class, ast);
        if (!(enclosingClass instanceof ClassNode)) {
            return items;
        }
        ClassNode classNode = (ClassNode) enclosingClass;
        if (!SpockUtils.isSpockSpecification(classNode)) {
            return items;
        }

        // Check if we're inside a method (for block labels) or at class level (for method snippets)
        ASTNode enclosingMethod = GroovyASTUtils.getEnclosingNodeOfType(offsetNode, MethodNode.class, ast);

        if (enclosingMethod instanceof MethodNode) {
            // Inside a method — offer block labels and where:-specific snippets
            addBlockLabelCompletions(items);
            // Also offer common Spock assertion helpers
            addSpockAssertionCompletions(items);
        } else {
            // At class level — offer feature method snippets and lifecycle methods
            addFeatureMethodSnippets(items);
            addLifecycleMethodSnippets(items);
        }

        // Always offer Spock annotations inside a Spock spec
        addSpockAnnotationCompletions(items);

        return items;
    }

    /**
     * Adds Spock block label completions (given:, when:, then:, etc.)
     */
    private void addBlockLabelCompletions(List<CompletionItem> items) {
        for (String label : SpockUtils.SPOCK_BLOCK_LABELS) {
            if ("where".equals(label)) {
                // where: gets its own richer snippet — see addWhereBlockCompletions()
                continue;
            }
            CompletionItem item = new CompletionItem();
            item.setLabel(label + ":");
            item.setKind(CompletionItemKind.Keyword);
            item.setInsertText(label + ":\n$0");
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setSortText("0_" + label); // prioritize block labels
            String description = SpockUtils.getBlockDescription(label);
            if (description != null) {
                item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, description));
            }
            item.setDetail("Spock block label");
            items.add(item);
        }

        // where: block with dedicated data-table snippet
        addWhereBlockCompletions(items);
    }

    /**
     * Adds where:-specific completions: the block label itself (with a data
     * table template), plus data table, data pipe, and derived variable snippets.
     */
    private void addWhereBlockCompletions(List<CompletionItem> items) {
        String whereDesc = SpockUtils.getBlockDescription("where");

        // where: with inline data table
        CompletionItem whereTable = new CompletionItem();
        whereTable.setLabel("where: (data table)");
        whereTable.setKind(CompletionItemKind.Keyword);
        whereTable.setInsertText(
                "where:\n" +
                "${1:a} | ${2:b} || ${3:expected}\n" +
                "${4:1} | ${5:2} || ${6:3}\n" +
                "${7:4} | ${8:5} || ${9:9}");
        whereTable.setInsertTextFormat(InsertTextFormat.Snippet);
        whereTable.setSortText("0_where_1");
        whereTable.setDetail("Spock where: block (data table)");
        whereTable.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Inserts a `where:` block with a **data table**.\n\n" +
                "Use `|` to separate input columns and `||` to separate inputs from expected values.\n\n" +
                "```groovy\nwhere:\na | b || expected\n1 | 2 || 3\n4 | 5 || 9\n```"));
        items.add(whereTable);

        // where: with data pipes
        CompletionItem wherePipe = new CompletionItem();
        wherePipe.setLabel("where: (data pipes)");
        wherePipe.setKind(CompletionItemKind.Keyword);
        wherePipe.setInsertText(
                "where:\n" +
                "${1:input} << [${2:value1, value2, value3}]\n" +
                "${3:expected} << [${4:result1, result2, result3}]");
        wherePipe.setInsertTextFormat(InsertTextFormat.Snippet);
        wherePipe.setSortText("0_where_2");
        wherePipe.setDetail("Spock where: block (data pipes)");
        wherePipe.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Inserts a `where:` block with **data pipes** (`<<`).\n\n" +
                "Each variable is fed from its own data provider (list, range, closure, etc.).\n\n" +
                "```groovy\nwhere:\ninput << [1, 4, 7]\nexpected << [2, 5, 8]\n```"));
        items.add(wherePipe);

        // plain where: label (for manual content)
        CompletionItem wherePlain = new CompletionItem();
        wherePlain.setLabel("where:");
        wherePlain.setKind(CompletionItemKind.Keyword);
        wherePlain.setInsertText("where:\n$0");
        wherePlain.setInsertTextFormat(InsertTextFormat.Snippet);
        wherePlain.setSortText("0_where_3");
        wherePlain.setDetail("Spock block label");
        if (whereDesc != null) {
            wherePlain.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, whereDesc));
        }
        items.add(wherePlain);

        // Standalone data table row (inside an existing where: block)
        CompletionItem tableRow = new CompletionItem();
        tableRow.setLabel("data table row");
        tableRow.setKind(CompletionItemKind.Snippet);
        tableRow.setInsertText("${1:val1} | ${2:val2} || ${3:expected}");
        tableRow.setInsertTextFormat(InsertTextFormat.Snippet);
        tableRow.setSortText("0_where_row");
        tableRow.setDetail("Spock: add data table row");
        tableRow.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Adds a new row to a Spock data table inside a `where:` block.\n\n" +
                "Adjust the number of columns to match the table header."));
        items.add(tableRow);

        // Derived data variable
        CompletionItem derived = new CompletionItem();
        derived.setLabel("derived data variable");
        derived.setKind(CompletionItemKind.Snippet);
        derived.setInsertText("${1:derived} = ${2:expression}");
        derived.setInsertTextFormat(InsertTextFormat.Snippet);
        derived.setSortText("0_where_derived");
        derived.setDetail("Spock: derived data variable");
        derived.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "A **derived data variable** is computed from other data variables " +
                "rather than supplied directly.\n\n" +
                "```groovy\nwhere:\na | b\n1 | 2\n3 | 4\nexpected = a + b\n```"));
        items.add(derived);

        // Multi-assignment from data pipe
        CompletionItem multiAssign = new CompletionItem();
        multiAssign.setLabel("multi-assignment data pipe");
        multiAssign.setKind(CompletionItemKind.Snippet);
        multiAssign.setInsertText("[${1:a}, ${2:b}, ${3:expected}] << [\n\t[${4:1, 2, 3}],\n\t[${5:4, 5, 9}],\n\t[${6:7, 8, 15}]\n]");
        multiAssign.setInsertTextFormat(InsertTextFormat.Snippet);
        multiAssign.setSortText("0_where_multi");
        multiAssign.setDetail("Spock: multi-variable data pipe");
        multiAssign.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Assigns **multiple variables** at once from a list of tuples.\n\n" +
                "```groovy\nwhere:\n[a, b, expected] << [\n    [1, 2, 3],\n    [4, 5, 9],\n]\n```"));
        items.add(multiAssign);

        // Data pipe from method/closure
        CompletionItem pipeClosure = new CompletionItem();
        pipeClosure.setLabel("data pipe (custom source)");
        pipeClosure.setKind(CompletionItemKind.Snippet);
        pipeClosure.setInsertText("${1:variable} << ${2:dataProvider()}");
        pipeClosure.setInsertTextFormat(InsertTextFormat.Snippet);
        pipeClosure.setSortText("0_where_pipe_src");
        pipeClosure.setDetail("Spock: data pipe from custom source");
        pipeClosure.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Feeds a data variable from any `Iterable`, `Iterator`, `Enumeration`, " +
                "or anything Groovy can iterate over.\n\n" +
                "```groovy\nwhere:\nname << sql.rows('SELECT name FROM users')\n```"));
        items.add(pipeClosure);
    }

    /**
     * Adds common Spock assertion method completions inside feature methods.
     */
    private void addSpockAssertionCompletions(List<CompletionItem> items) {
        // thrown()
        CompletionItem thrown = new CompletionItem();
        thrown.setLabel("thrown");
        thrown.setKind(CompletionItemKind.Method);
        thrown.setInsertText("thrown(${1:ExceptionType})");
        thrown.setInsertTextFormat(InsertTextFormat.Snippet);
        thrown.setDetail("Spock: expect exception");
        thrown.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Asserts that executing the `when:` block throws the specified exception.\n\n" +
                        "```groovy\nwhen:\nfoo.bar()\n\nthen:\nthrown(IllegalArgumentException)\n```"));
        thrown.setSortText("1_thrown");
        items.add(thrown);

        // notThrown()
        CompletionItem notThrown = new CompletionItem();
        notThrown.setLabel("notThrown");
        notThrown.setKind(CompletionItemKind.Method);
        notThrown.setInsertText("notThrown(${1:ExceptionType})");
        notThrown.setInsertTextFormat(InsertTextFormat.Snippet);
        notThrown.setDetail("Spock: no exception expected");
        notThrown.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Asserts that executing the `when:` block does **not** throw the specified exception."));
        notThrown.setSortText("1_notThrown");
        items.add(notThrown);

        // noExceptionThrown()
        CompletionItem noException = new CompletionItem();
        noException.setLabel("noExceptionThrown");
        noException.setKind(CompletionItemKind.Method);
        noException.setInsertText("noExceptionThrown()");
        noException.setInsertTextFormat(InsertTextFormat.Snippet);
        noException.setDetail("Spock: no exception at all");
        noException.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Asserts that executing the `when:` block does not throw any exception."));
        noException.setSortText("1_noExceptionThrown");
        items.add(noException);

        // old()
        CompletionItem old = new CompletionItem();
        old.setLabel("old");
        old.setKind(CompletionItemKind.Method);
        old.setInsertText("old(${1:expression})");
        old.setInsertTextFormat(InsertTextFormat.Snippet);
        old.setDetail("Spock: old value");
        old.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "References the **old** value of an expression (before the `when:` block executed).\n\n" +
                        "```groovy\nthen:\nlist.size() == old(list.size()) + 1\n```"));
        old.setSortText("1_old");
        items.add(old);

        // Mock()
        CompletionItem mock = new CompletionItem();
        mock.setLabel("Mock");
        mock.setKind(CompletionItemKind.Method);
        mock.setInsertText("Mock(${1:Type})");
        mock.setInsertTextFormat(InsertTextFormat.Snippet);
        mock.setDetail("Spock: create mock");
        mock.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a mock object of the specified type for interaction-based testing.\n\n" +
                        "```groovy\ndef subscriber = Mock(Subscriber)\n```"));
        mock.setSortText("1_Mock");
        items.add(mock);

        // Stub()
        CompletionItem stub = new CompletionItem();
        stub.setLabel("Stub");
        stub.setKind(CompletionItemKind.Method);
        stub.setInsertText("Stub(${1:Type})");
        stub.setInsertTextFormat(InsertTextFormat.Snippet);
        stub.setDetail("Spock: create stub");
        stub.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a stub object. Stubs support only stubbing, not mocking interactions.\n\n" +
                        "```groovy\ndef subscriber = Stub(Subscriber)\nsubscriber.receive(_) >> \"ok\"\n```"));
        stub.setSortText("1_Stub");
        items.add(stub);

        // Spy()
        CompletionItem spy = new CompletionItem();
        spy.setLabel("Spy");
        spy.setKind(CompletionItemKind.Method);
        spy.setInsertText("Spy(${1:Type})");
        spy.setInsertTextFormat(InsertTextFormat.Snippet);
        spy.setDetail("Spock: create spy");
        spy.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a spy based on a real object. Calls are forwarded to the real object " +
                        "but can be stubbed or verified.\n\n" +
                        "```groovy\ndef subscriber = Spy(SubscriberImpl)\n```"));
        spy.setSortText("1_Spy");
        items.add(spy);

        // interaction {}
        CompletionItem interaction = new CompletionItem();
        interaction.setLabel("interaction");
        interaction.setKind(CompletionItemKind.Snippet);
        interaction.setInsertText("interaction {\n\t$0\n}");
        interaction.setInsertTextFormat(InsertTextFormat.Snippet);
        interaction.setDetail("Spock: interaction block");
        interaction.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Groups mock interactions into a block. Useful in `then:` blocks when " +
                        "interactions need to be declared together."));
        interaction.setSortText("1_interaction");
        items.add(interaction);

        // with() {}
        CompletionItem with = new CompletionItem();
        with.setLabel("with");
        with.setKind(CompletionItemKind.Method);
        with.setInsertText("with(${1:target}) {\n\t$0\n}");
        with.setInsertTextFormat(InsertTextFormat.Snippet);
        with.setDetail("Spock: with block");
        with.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Evaluates conditions against a target object. Avoids repeated references to the same object.\n\n" +
                        "```groovy\nwith(response) {\n    status == 200\n    body.contains(\"ok\")\n}\n```"));
        with.setSortText("1_with");
        items.add(with);

        // verifyAll() {}
        CompletionItem verifyAll = new CompletionItem();
        verifyAll.setLabel("verifyAll");
        verifyAll.setKind(CompletionItemKind.Method);
        verifyAll.setInsertText("verifyAll {\n\t$0\n}");
        verifyAll.setInsertTextFormat(InsertTextFormat.Snippet);
        verifyAll.setDetail("Spock: verify all conditions");
        verifyAll.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Evaluates **all** conditions even if earlier ones fail (soft assertions).\n\n" +
                        "```groovy\nverifyAll {\n    result.name == \"test\"\n    result.size == 42\n}\n```"));
        verifyAll.setSortText("1_verifyAll");
        items.add(verifyAll);
    }

    /**
     * Adds Spock feature method snippet completions at class level.
     */
    private void addFeatureMethodSnippets(List<CompletionItem> items) {
        // given-when-then pattern
        CompletionItem gwt = new CompletionItem();
        gwt.setLabel("def \"feature\" (given-when-then)");
        gwt.setKind(CompletionItemKind.Snippet);
        gwt.setInsertText(
                "def \"${1:should do something}\"() {\n" +
                "\tgiven:\n" +
                "\t${2:// setup}\n\n" +
                "\twhen:\n" +
                "\t${3:// stimulus}\n\n" +
                "\tthen:\n" +
                "\t${4:// response}\n" +
                "}");
        gwt.setInsertTextFormat(InsertTextFormat.Snippet);
        gwt.setDetail("Spock feature method (given-when-then)");
        gwt.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a Spock feature method with `given:`-`when:`-`then:` blocks.\n\n" +
                        "This is the most common pattern for testing behavior."));
        gwt.setSortText("0_feature_gwt");
        items.add(gwt);

        // expect pattern
        CompletionItem expect = new CompletionItem();
        expect.setLabel("def \"feature\" (expect)");
        expect.setKind(CompletionItemKind.Snippet);
        expect.setInsertText(
                "def \"${1:should satisfy condition}\"() {\n" +
                "\texpect:\n" +
                "\t${2:// condition}\n" +
                "}");
        expect.setInsertTextFormat(InsertTextFormat.Snippet);
        expect.setDetail("Spock feature method (expect)");
        expect.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a Spock feature method with an `expect:` block.\n\n" +
                        "Best for simple stimulus-response tests where the action and assertion " +
                        "can be combined in one expression."));
        expect.setSortText("0_feature_expect");
        items.add(expect);

        // data-driven pattern
        CompletionItem dataDriven = new CompletionItem();
        dataDriven.setLabel("def \"feature\" (data-driven)");
        dataDriven.setKind(CompletionItemKind.Snippet);
        dataDriven.setInsertText(
                "def \"${1:should compute #expected from #input}\"() {\n" +
                "\texpect:\n" +
                "\t${2:result} == ${3:expected}\n\n" +
                "\twhere:\n" +
                "\t${4:input} | ${5:expected}\n" +
                "\t${6:1}     | ${7:2}\n" +
                "\t${8:3}     | ${9:4}\n" +
                "}");
        dataDriven.setInsertTextFormat(InsertTextFormat.Snippet);
        dataDriven.setDetail("Spock data-driven feature method");
        dataDriven.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a parameterized Spock feature method with a `where:` data table.\n\n" +
                        "Use `@Unroll` to get individual test names for each data row."));
        dataDriven.setSortText("0_feature_data");
        items.add(dataDriven);

        // exception testing pattern
        CompletionItem exceptionTest = new CompletionItem();
        exceptionTest.setLabel("def \"feature\" (exception)");
        exceptionTest.setKind(CompletionItemKind.Snippet);
        exceptionTest.setInsertText(
                "def \"${1:should throw exception}\"() {\n" +
                "\twhen:\n" +
                "\t${2:// action}\n\n" +
                "\tthen:\n" +
                "\tthrown(${3:ExceptionType})\n" +
                "}");
        exceptionTest.setInsertTextFormat(InsertTextFormat.Snippet);
        exceptionTest.setDetail("Spock exception feature method");
        exceptionTest.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a Spock feature method that verifies an exception is thrown."));
        exceptionTest.setSortText("0_feature_exception");
        items.add(exceptionTest);

        // interaction testing pattern
        CompletionItem interactionTest = new CompletionItem();
        interactionTest.setLabel("def \"feature\" (interaction)");
        interactionTest.setKind(CompletionItemKind.Snippet);
        interactionTest.setInsertText(
                "def \"${1:should interact with mock}\"() {\n" +
                "\tgiven:\n" +
                "\tdef ${2:mock} = Mock(${3:Type})\n\n" +
                "\twhen:\n" +
                "\t${4:// action}\n\n" +
                "\tthen:\n" +
                "\t${5:1} * ${2:mock}.${6:method}(${7:_})\n" +
                "}");
        interactionTest.setInsertTextFormat(InsertTextFormat.Snippet);
        interactionTest.setDetail("Spock interaction feature method");
        interactionTest.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN,
                "Creates a Spock feature method that tests mock interactions.\n\n" +
                        "The `then:` block verifies that the mock's method was called " +
                        "the expected number of times with the expected arguments."));
        interactionTest.setSortText("0_feature_interaction");
        items.add(interactionTest);
    }

    /**
     * Adds Spock lifecycle method snippet completions.
     */
    private void addLifecycleMethodSnippets(List<CompletionItem> items) {
        addLifecycleSnippet(items, "setup", "Runs before each feature method",
                "def setup() {\n\t$0\n}");
        addLifecycleSnippet(items, "cleanup", "Runs after each feature method",
                "def cleanup() {\n\t$0\n}");
        addLifecycleSnippet(items, "setupSpec", "Runs once before the first feature method",
                "def setupSpec() {\n\t$0\n}");
        addLifecycleSnippet(items, "cleanupSpec", "Runs once after the last feature method",
                "def cleanupSpec() {\n\t$0\n}");
    }

    private void addLifecycleSnippet(List<CompletionItem> items, String name, String detail, String snippet) {
        CompletionItem item = new CompletionItem();
        item.setLabel(name + "()");
        item.setKind(CompletionItemKind.Snippet);
        item.setInsertText(snippet);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setDetail("Spock: " + detail);
        String doc = SpockUtils.getLifecycleMethodDescription(name);
        if (doc != null) {
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, doc));
        }
        item.setSortText("0_lifecycle_" + name);
        items.add(item);
    }

    /**
     * Adds Spock annotation completions (simple names for common annotations).
     */
    private void addSpockAnnotationCompletions(List<CompletionItem> items) {
        addAnnotationItem(items, "Unroll", "spock.lang.Unroll",
                "Enables individual test naming for data-driven features.\n\n" +
                        "```groovy\n@Unroll\ndef \"max of #a and #b is #c\"() { ... }\n```");
        addAnnotationItem(items, "Shared", "spock.lang.Shared",
                "Marks a field as shared across all feature methods.\n\n" +
                        "```groovy\n@Shared def resource = new ExpensiveResource()\n```");
        addAnnotationItem(items, "Stepwise", "spock.lang.Stepwise",
                "Runs feature methods in declaration order; skips remaining on failure.");
        addAnnotationItem(items, "Timeout", "spock.lang.Timeout",
                "Sets a timeout for the feature method or specification.\n\n" +
                        "```groovy\n@Timeout(5)\ndef \"should complete within 5 seconds\"() { ... }\n```");
        addAnnotationItem(items, "Ignore", "spock.lang.Ignore",
                "Skips the annotated feature method or specification.");
        addAnnotationItem(items, "IgnoreRest", "spock.lang.IgnoreRest",
                "Runs only this feature method, ignoring all others.");
        addAnnotationItem(items, "IgnoreIf", "spock.lang.IgnoreIf",
                "Conditionally ignores the feature based on a closure condition.\n\n" +
                        "```groovy\n@IgnoreIf({ System.getenv('CI') })\n```");
        addAnnotationItem(items, "Requires", "spock.lang.Requires",
                "Runs the feature only when the condition is met.\n\n" +
                        "```groovy\n@Requires({ os.linux })\n```");
        addAnnotationItem(items, "Retry", "spock.lang.Retry",
                "Retries a failed feature method up to a specified count.");
        addAnnotationItem(items, "Title", "spock.lang.Title",
                "Sets a human-readable title for the specification.");
        addAnnotationItem(items, "Narrative", "spock.lang.Narrative",
                "Sets a longer narrative description for the specification.");
        addAnnotationItem(items, "Issue", "spock.lang.Issue",
                "Links the feature to an issue tracker entry.\n\n" +
                        "```groovy\n@Issue(\"https://issues.example.com/123\")\n```");
        addAnnotationItem(items, "See", "spock.lang.See",
                "Links to external references or documentation.");
        addAnnotationItem(items, "Subject", "spock.lang.Subject",
                "Declares the class under test.\n\n" +
                        "```groovy\n@Subject\nCalculator calculator = new Calculator()\n```");
        addAnnotationItem(items, "AutoCleanup", "spock.lang.AutoCleanup",
                "Automatically calls `close()` (or another method) on the field after the spec.");
    }

    private void addAnnotationItem(List<CompletionItem> items, String simpleName, String fqn, String description) {
        CompletionItem item = new CompletionItem();
        item.setLabel("@" + simpleName);
        item.setKind(CompletionItemKind.Reference);
        item.setInsertText("@" + simpleName);
        item.setDetail("Spock: " + fqn);
        item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, description));
        item.setSortText("2_" + simpleName);
        items.add(item);
    }
}
