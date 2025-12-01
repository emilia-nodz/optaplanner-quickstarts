package org.acme.employeescheduling.domain;

import java.util.Set;

import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;

import org.optaplanner.core.api.domain.lookup.PlanningId;

@Entity
public class Employee {
    @Id
    @PlanningId
    Long id;

    String name;

    @ElementCollection(fetch = FetchType.EAGER)
    Set<String> skillSet;

    public Employee() {

    }

    public Employee(Long id, String name, Set<String> skillSet) {
        this.id = id;
        this.name = name;
        this.skillSet = skillSet;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Set<String> getSkillSet() {
        return skillSet;
    }

    public void setSkillSet(Set<String> skillSet) {
        this.skillSet = skillSet;
    }

    @Override
    public String toString() {
        return name;
    }
}
