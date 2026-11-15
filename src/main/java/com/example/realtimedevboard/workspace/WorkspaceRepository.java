package com.example.realtimedevboard.workspace;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    List<Workspace> findByMembers_UsernameOrderById(String username);

    Optional<Workspace> findByIdAndMembers_Username(Long id, String username);
}
