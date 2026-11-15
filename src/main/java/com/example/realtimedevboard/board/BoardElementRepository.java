package com.example.realtimedevboard.board;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BoardElementRepository extends JpaRepository<BoardElement, Long> {

    List<BoardElement> findByBoardIdOrderById(Long boardId);

    Optional<BoardElement> findByIdAndBoardId(Long id, Long boardId);
}
