package io.dataease.company.bi.permission;

import io.dataease.company.bi.acl.CompanyRelationLevel;

public enum CompanyResourceRelation {
    NONE,
    VISITOR,
    OWNER;

    public int rank() {
        return ordinal();
    }

    public static CompanyResourceRelation max(CompanyResourceRelation left, CompanyResourceRelation right) {
        CompanyResourceRelation a = left == null ? NONE : left;
        CompanyResourceRelation b = right == null ? NONE : right;
        return a.rank() >= b.rank() ? a : b;
    }

    public static CompanyResourceRelation fromLevel(String relationLevel) {
        if (relationLevel == null) {
            return NONE;
        }
        try {
            CompanyRelationLevel level = CompanyRelationLevel.valueOf(relationLevel);
            return switch (level) {
                case OWNER -> OWNER;
                case VISITOR -> VISITOR;
            };
        } catch (RuntimeException ignored) {
            return NONE;
        }
    }

    public boolean hasAccess() {
        return this != NONE;
    }
}
