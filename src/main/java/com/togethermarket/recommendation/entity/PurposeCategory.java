package com.togethermarket.recommendation.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "purpose_categories")
public class PurposeCategory {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "parent_id", nullable = true)
  private Long parentId;

  @Column(name = "name", nullable = false, length = 100)
  private String name;

  protected PurposeCategory() {}

  public PurposeCategory(Long parentId, String name) {
    this.parentId = parentId;
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public Long getParentId() {
    return parentId;
  }

  public String getName() {
    return name;
  }
}
