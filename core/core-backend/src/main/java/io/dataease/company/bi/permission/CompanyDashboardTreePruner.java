package io.dataease.company.bi.permission;

import io.dataease.model.BusiNodeVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CompanyDashboardTreePruner {

    private CompanyDashboardTreePruner() {
    }

    public static List<BusiNodeVO> prune(List<BusiNodeVO> tree, Set<Long> accessibleDashboardIds,
                                         DashboardPermissionProjection leafProjection,
                                         DashboardPermissionProjection folderShell) {
        return prune(tree, accessibleDashboardIds, Set.of(), leafProjection, folderShell, folderShell);
    }

    public static List<BusiNodeVO> prune(List<BusiNodeVO> tree,
                                         Set<Long> accessibleDashboardIds,
                                         Set<Long> authorizedFolderIds,
                                         DashboardPermissionProjection leafProjection,
                                         DashboardPermissionProjection authorizedFolder,
                                         DashboardPermissionProjection folderShell) {
        if (tree == null || tree.isEmpty()) {
            return List.of();
        }
        Set<Long> accessible = accessibleDashboardIds == null ? Set.of() : accessibleDashboardIds;
        Set<Long> folders = authorizedFolderIds == null ? Set.of() : authorizedFolderIds;
        List<BusiNodeVO> kept = new ArrayList<>();
        for (BusiNodeVO node : tree) {
            BusiNodeVO next = pruneNode(node, accessible, folders, leafProjection, authorizedFolder, folderShell);
            if (next != null) {
                kept.add(next);
            }
        }
        return kept;
    }

    private static BusiNodeVO pruneNode(BusiNodeVO node, Set<Long> accessible, Set<Long> authorizedFolders,
                                        DashboardPermissionProjection leafProjection,
                                        DashboardPermissionProjection authorizedFolder,
                                        DashboardPermissionProjection folderShell) {
        if (node == null) {
            return null;
        }
        boolean leaf = Boolean.TRUE.equals(node.getLeaf());
        if (leaf) {
            if (node.getId() == null || !accessible.contains(node.getId())) {
                return null;
            }
            apply(node, leafProjection);
            node.setChildren(null);
            return node;
        }
        List<BusiNodeVO> children = node.getChildren();
        List<BusiNodeVO> keptChildren = new ArrayList<>();
        if (children != null) {
            for (BusiNodeVO child : children) {
                BusiNodeVO next = pruneNode(child, accessible, authorizedFolders, leafProjection, authorizedFolder, folderShell);
                if (next != null) {
                    keptChildren.add(next);
                }
            }
        }
        boolean root = node.getId() != null && node.getId() == 0L;
        boolean authorized = node.getId() != null && authorizedFolders.contains(node.getId());
        if (keptChildren.isEmpty() && !root && !authorized) {
            return null;
        }
        node.setChildren(keptChildren);
        if (!root) {
            apply(node, authorized ? authorizedFolder : folderShell);
        }
        return node;
    }

    private static void apply(BusiNodeVO node, DashboardPermissionProjection projection) {
        if (projection == null) {
            return;
        }
        node.setWeight(projection.weight());
        node.setExt(projection.ext());
    }
}
