package dev.omatheusmesmo.vintelli.tui.editor;

import java.util.ArrayDeque;
import java.util.Deque;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Document;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import dev.omatheusmesmo.vintelli.tui.editor.HoverText.Face;

/**
 * Walks the CommonMark tree of a documentation text into {@link HoverRows}: headings and strong text in bold, code
 * spans and fenced blocks in the code face, links as their text, lists with their markers, and the HTML blocks and
 * inline tags through {@link HoverHtml}. Emphasis, images and the other containers keep only their text.
 */
final class HoverMarkdown extends AbstractVisitor {

    private static final Parser PARSER = Parser.builder().build();
    private static final int BULLET = -1;

    private final HoverRows rows;
    private final Deque<Integer> lists = new ArrayDeque<>();

    private HoverMarkdown(HoverRows rows) {
        this.rows = rows;
    }

    static void render(String markdown, HoverRows rows) {
        Node document = PARSER.parse(markdown);
        document.accept(new HoverMarkdown(rows));
    }

    @Override
    public void visit(Document document) {
        visitChildren(document);
    }

    @Override
    public void visit(Heading heading) {
        rows.block();
        rows.enter(Face.BOLD);
        visitChildren(heading);
        rows.leave(Face.BOLD);
        rows.endBlock();
    }

    @Override
    public void visit(Paragraph paragraph) {
        rows.block();
        visitChildren(paragraph);
        rows.endBlock();
    }

    @Override
    public void visit(BlockQuote quote) {
        rows.block();
        visitChildren(quote);
        rows.endBlock();
    }

    @Override
    public void visit(BulletList list) {
        rows.block();
        lists.push(BULLET);
        visitChildren(list);
        lists.pop();
    }

    @Override
    public void visit(OrderedList list) {
        rows.block();
        lists.push(list.getStartNumber());
        visitChildren(list);
        lists.pop();
    }

    @Override
    public void visit(ListItem item) {
        rows.item(nextMarker());
        visitChildren(item);
        rows.endBlock();
    }

    @Override
    public void visit(FencedCodeBlock block) {
        codeBlock(block.getLiteral());
    }

    @Override
    public void visit(IndentedCodeBlock block) {
        codeBlock(block.getLiteral());
    }

    @Override
    public void visit(ThematicBreak thematicBreak) {
        rows.block();
    }

    @Override
    public void visit(HtmlBlock block) {
        rows.block();
        HoverHtml.apply(block.getLiteral(), rows);
        rows.endBlock();
    }

    @Override
    public void visit(HtmlInline inline) {
        HoverHtml.apply(inline.getLiteral(), rows);
    }

    @Override
    public void visit(Code code) {
        rows.enter(Face.CODE);
        rows.text(code.getLiteral());
        rows.leave(Face.CODE);
    }

    @Override
    public void visit(StrongEmphasis emphasis) {
        rows.enter(Face.BOLD);
        visitChildren(emphasis);
        rows.leave(Face.BOLD);
    }

    @Override
    public void visit(Text text) {
        rows.text(text.getLiteral());
    }

    @Override
    public void visit(SoftLineBreak softLineBreak) {
        rows.text(" ");
    }

    @Override
    public void visit(HardLineBreak hardLineBreak) {
        rows.lineBreak();
    }

    private void codeBlock(String literal) {
        rows.block();
        rows.enter(Face.CODE);
        rows.enterPreformatted();
        rows.text(literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal);
        rows.leavePreformatted();
        rows.leave(Face.CODE);
        rows.endBlock();
    }

    private String nextMarker() {
        Integer list = lists.peek();
        if (list == null || list == BULLET) {
            return "- ";
        }
        lists.pop();
        lists.push(list + 1);
        return list + ". ";
    }
}
