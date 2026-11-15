package com.example.realtimedevboard.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findByWorkspaceIdOrderById(Long workspaceId);

    /** The only way boards are loaded for a user: the membership check is part of the query. */
    Optional<Board> findByIdAndWorkspace_Members_Username(Long id, String username);
}
