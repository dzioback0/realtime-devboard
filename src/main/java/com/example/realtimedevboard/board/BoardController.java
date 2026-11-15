package com.example.realtimedevboard.board;

import com.example.realtimedevboard.execution.ExecutionResult;
import com.example.realtimedevboard.execution.ExecutionService;
import com.example.realtimedevboard.workspace.Workspace;
import com.example.realtimedevboard.workspace.WorkspaceRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every endpoint resolves the board through the caller's workspace memberships; a client-supplied id
 * of another tenant's board/workspace gives 404 (same as non-existent, so ids do not leak).
 * Concurrent edits: last write wins per whole element; every change is broadcast to the board's sockets.
 */
@RestController
@RequestMapping("/api")
public class BoardController {

    static final Set<String> SHAPE_KINDS = Set.of("RECT", "CIRCLE");

    private final WorkspaceRepository workspaces;
    private final BoardRepository boards;
    private final BoardElementRepository elements;
    private final ExecutionService execution;
    private final BoardSocketHandler sockets;

    public BoardController(WorkspaceRepository workspaces, BoardRepository boards, BoardElementRepository elements,
                           ExecutionService execution, BoardSocketHandler sockets) {
        this.workspaces = workspaces;
        this.boards = boards;
        this.elements = elements;
        this.execution = execution;
        this.sockets = sockets;
    }

    record BoardSummary(Long id, String name) {
    }

    record WorkspaceView(Long id, String name, List<BoardSummary> boards) {
    }

    record BoardView(Long id, String name, Long workspaceId, String workspaceName, List<BoardElement> elements) {
    }

    record NewBoard(@NotBlank @Size(max = 100) String name) {
    }

    record ElementRequest(@NotNull ElementType type,
                          @Min(0) @Max(10_000) int x, @Min(0) @Max(10_000) int y,
                          @Min(20) @Max(4_000) int width, @Min(20) @Max(4_000) int height,
                          @Size(max = BoardElement.MAX_CONTENT) String content,
                          String shape,
                          @Min(0) @Max(10_000) Integer endX, @Min(0) @Max(10_000) Integer endY) {
    }

    @GetMapping("/me")
    Map<String, String> me(Principal user) {
        return Map.of("username", user.getName());
    }

    @GetMapping("/workspaces")
    @Transactional(readOnly = true)
    List<WorkspaceView> myWorkspaces(Principal user) {
        return workspaces.findByMembers_UsernameOrderById(user.getName()).stream()
                .map(w -> new WorkspaceView(w.getId(), w.getName(), boards.findByWorkspaceIdOrderById(w.getId()).stream()
                        .map(b -> new BoardSummary(b.getId(), b.getName())).toList()))
                .toList();
    }

    @PostMapping("/workspaces/{workspaceId}/boards")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    BoardSummary createBoard(@PathVariable Long workspaceId, @Valid @RequestBody NewBoard request, Principal user) {
        Workspace workspace = workspaces.findByIdAndMembers_Username(workspaceId, user.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Board board = boards.save(new Board(workspace, request.name().strip()));
        return new BoardSummary(board.getId(), board.getName());
    }

    @GetMapping("/boards/{boardId}")
    @Transactional(readOnly = true)
    BoardView board(@PathVariable Long boardId, Principal user) {
        Board board = accessibleBoard(boardId, user);
        return new BoardView(board.getId(), board.getName(), board.getWorkspace().getId(),
                board.getWorkspace().getName(), elements.findByBoardIdOrderById(boardId));
    }

    @PostMapping("/boards/{boardId}/elements")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    BoardElement addElement(@PathVariable Long boardId, @Valid @RequestBody ElementRequest r, Principal user) {
        Board board = accessibleBoard(boardId, user);
        requireValidKindFields(r.type(), r);
        BoardElement element = new BoardElement(board, r.type(), 0, 0, 20, 20, null);
        apply(element, r);
        elements.save(element);
        sockets.publish(boardId, Map.of("type", "upsert", "element", element));
        return element;
    }

    @PutMapping("/boards/{boardId}/elements/{elementId}")
    @Transactional
    BoardElement updateElement(@PathVariable Long boardId, @PathVariable Long elementId,
                               @Valid @RequestBody ElementRequest r, Principal user) {
        BoardElement element = accessibleElement(boardId, elementId, user);
        requireValidKindFields(element.getType(), r);
        apply(element, r);
        sockets.publish(boardId, Map.of("type", "upsert", "element", element));
        return element;
    }

    @DeleteMapping("/boards/{boardId}/elements/{elementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteElement(@PathVariable Long boardId, @PathVariable Long elementId, Principal user) {
        elements.delete(accessibleElement(boardId, elementId, user));
        sockets.publish(boardId, Map.of("type", "delete", "id", elementId));
    }

    /** Not transactional on purpose: execution may take ~25 s and must not hold a DB connection. */
    @PostMapping("/boards/{boardId}/elements/{elementId}/run")
    ExecutionResult run(@PathVariable Long boardId, @PathVariable Long elementId, Principal user) {
        BoardElement element = accessibleElement(boardId, elementId, user);
        if (element.getType() != ElementType.CODE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only code elements can be run");
        }
        ExecutionResult result = execution.execute("java", element.getContent());
        // Reload: the code may have been edited while it ran; only the result columns are ours to change.
        elements.findByIdAndBoardId(elementId, boardId).ifPresent(fresh -> {
            fresh.recordRun(result);
            elements.save(fresh);
            sockets.publish(boardId, Map.of("type", "upsert", "element", fresh));
        });
        return result;
    }

    /** A SHAPE needs a known kind, an ARROW needs its end point; other types ignore these fields. */
    private static void requireValidKindFields(ElementType type, ElementRequest r) {
        // Set.of(...).contains(null) throws, hence the explicit null check
        if (type == ElementType.SHAPE && (r.shape() == null || !SHAPE_KINDS.contains(r.shape()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Shape must be one of " + SHAPE_KINDS);
        }
        if (type == ElementType.ARROW && (r.endX() == null || r.endY() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arrow needs endX and endY");
        }
    }

    private static void apply(BoardElement element, ElementRequest r) {
        ElementType type = element.getType();
        element.update(r.x(), r.y(), r.width(), r.height(), r.content(),
                type == ElementType.SHAPE ? r.shape() : null,
                type == ElementType.ARROW ? r.endX() : null,
                type == ElementType.ARROW ? r.endY() : null);
    }

    private Board accessibleBoard(Long boardId, Principal user) {
        return boards.findByIdAndWorkspace_Members_Username(boardId, user.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private BoardElement accessibleElement(Long boardId, Long elementId, Principal user) {
        accessibleBoard(boardId, user);
        return elements.findByIdAndBoardId(elementId, boardId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
