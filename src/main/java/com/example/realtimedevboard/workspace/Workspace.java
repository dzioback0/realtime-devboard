package com.example.realtimedevboard.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;

import java.util.HashSet;
import java.util.Set;

/** The tenant. Access to everything below it (boards, elements, runs) is decided by membership. */
@Entity
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToMany
    @JoinTable(name = "workspace_member")
    private Set<AppUser> members = new HashSet<>();

    protected Workspace() {
    }

    public Workspace(String name, Set<AppUser> members) {
        this.name = name;
        this.members = members;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
