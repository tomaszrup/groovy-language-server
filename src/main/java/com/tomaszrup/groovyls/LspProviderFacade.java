package com.tomaszrup.groovyls;

import com.tomaszrup.groovyls.compiler.ClasspathSymbolIndex;
import com.tomaszrup.groovyls.compiler.ast.ASTNodeVisitor;
import com.tomaszrup.groovyls.util.FileContentsTracker;
import com.tomaszrup.groovyls.util.JavaSourceLocator;
import org.codehaus.groovy.ast.ASTNode;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Thin composite facade that delegates to four focused sub-facades:
 * <ul>
 *   <li>{@link NavigationProviderFacade} — definition, type-definition, implementation, highlights, references</li>
 *   <li>{@link IntelligenceProviderFacade} — hover, completion, signature help</li>
 *   <li>{@link RefactoringProviderFacade} — rename, code actions, formatting</li>
 *   <li>{@link SymbolProviderFacade} — document symbols, workspace symbols, inlay hints, semantic tokens</li>
 * </ul>
 */
final class LspProviderFacade {
    private final NavigationProviderFacade navigation = new NavigationProviderFacade();
    private final IntelligenceProviderFacade intelligence = new IntelligenceProviderFacade();
    private final RefactoringProviderFacade refactoring;
    private final SymbolProviderFacade symbols;

    LspProviderFacade(FileContentsTracker fileContentsTracker) {
        this.refactoring = new RefactoringProviderFacade(fileContentsTracker);
        this.symbols = new SymbolProviderFacade(fileContentsTracker);
    }

    CompletableFuture<Hover> provideHover(ASTNodeVisitor visitor, TextDocumentIdentifier textDocument, Position position) {
        return intelligence.provideHover(visitor, textDocument, position);
    }

    CompletableFuture<Either<List<CompletionItem>, CompletionList>> provideCompletion(
            ASTNodeVisitor visitor,
            ClasspathSymbolIndex classpathSymbolIndex,
            Set<String> classpathSymbolClasspathElements,
            TextDocumentIdentifier textDocument,
            Position position) {
        return intelligence.provideCompletion(visitor, classpathSymbolIndex, classpathSymbolClasspathElements, textDocument, position);
    }

    List<CompletionItem> provideSpockCompletions(ASTNodeVisitor visitor, URI uri, Position position, ASTNode currentNode) {
        return intelligence.provideSpockCompletions(visitor, uri, position, currentNode);
    }

    CompletableFuture<Either<List<Location>, List<LocationLink>>> provideDefinition(
            ASTNodeVisitor visitor,
            JavaSourceLocator javaSourceLocator,
            TextDocumentIdentifier textDocument,
            Position position) {
        return navigation.provideDefinition(visitor, javaSourceLocator, textDocument, position);
    }

    CompletableFuture<SignatureHelp> provideSignatureHelp(ASTNodeVisitor visitor, TextDocumentIdentifier textDocument, Position position) {
        return intelligence.provideSignatureHelp(visitor, textDocument, position);
    }

    CompletableFuture<Either<List<Location>, List<LocationLink>>> provideTypeDefinition(
            ASTNodeVisitor visitor,
            JavaSourceLocator javaSourceLocator,
            TextDocumentIdentifier textDocument,
            Position position) {
        return navigation.provideTypeDefinition(visitor, javaSourceLocator, textDocument, position);
    }

    CompletableFuture<Either<List<Location>, List<LocationLink>>> provideImplementation(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Position position) {
        return navigation.provideImplementation(visitor, textDocument, position);
    }

    @SuppressWarnings("java:S1452")
    CompletableFuture<List<? extends DocumentHighlight>> provideDocumentHighlights(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Position position) {
        return navigation.provideDocumentHighlights(visitor, textDocument, position);
    }

    @SuppressWarnings("java:S1452")
    CompletableFuture<List<? extends Location>> provideReferences(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Position position) {
        return navigation.provideReferences(visitor, textDocument, position);
    }

    CompletableFuture<List<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>>> provideDocumentSymbols(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument) {
        return symbols.provideDocumentSymbols(visitor, textDocument);
    }

    CompletableFuture<List<WorkspaceSymbol>> provideWorkspaceSymbols(ASTNodeVisitor visitor, String query) {
        return symbols.provideWorkspaceSymbols(visitor, query);
    }

    CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> providePrepareRename(
            ASTNodeVisitor visitor,
            PrepareRenameParams params) {
        return refactoring.providePrepareRename(visitor, params);
    }

    CompletableFuture<WorkspaceEdit> provideRename(ASTNodeVisitor visitor, RenameParams params) {
        return refactoring.provideRename(visitor, params);
    }

    CompletableFuture<List<Either<Command, CodeAction>>> provideCodeActions(
            ASTNodeVisitor visitor,
            ClasspathSymbolIndex classpathSymbolIndex,
            Set<String> classpathSymbolClasspathElements,
            JavaSourceLocator javaSourceLocator,
            CodeActionParams params) {
        return refactoring.provideCodeActions(visitor, classpathSymbolIndex, classpathSymbolClasspathElements, javaSourceLocator, params);
    }

    List<Either<Command, CodeAction>> provideSpockCodeActions(ASTNodeVisitor visitor, CodeActionParams params) {
        return refactoring.provideSpockCodeActions(visitor, params);
    }

    CompletableFuture<List<InlayHint>> provideInlayHints(ASTNodeVisitor visitor, org.eclipse.lsp4j.InlayHintParams params) {
        return symbols.provideInlayHints(visitor, params);
    }

    CompletableFuture<SemanticTokens> provideSemanticTokensFull(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            boolean groovy4Compatibility) {
        return symbols.provideSemanticTokensFull(visitor, textDocument, groovy4Compatibility);
    }

    CompletableFuture<SemanticTokens> provideSemanticTokensRange(
            ASTNodeVisitor visitor,
            TextDocumentIdentifier textDocument,
            Range range,
            boolean groovy4Compatibility) {
        return symbols.provideSemanticTokensRange(visitor, textDocument, range, groovy4Compatibility);
    }

    CompletableFuture<List<TextEdit>> provideFormatting(DocumentFormattingParams params, String textForFormatting) {
        return refactoring.provideFormatting(params, textForFormatting);
    }
}
