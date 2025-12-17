package com.dev.jwtstudy.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.dev.jwtstudy.entities.Category;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

}
