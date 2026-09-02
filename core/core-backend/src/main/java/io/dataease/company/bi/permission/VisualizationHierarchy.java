package io.dataease.company.bi.permission;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class VisualizationHierarchy {

    public static final int MAX_DEPTH = 64;

    public record Node(Long id, Long pid, boolean folder, boolean dashboardLeaf) {
    }

    private final Map<Long, Node> byId;
    private final Map<Long, List<Long>> children;

    public VisualizationHierarchy(List<Node> nodes) {
        this.byId = new HashMap<>();
        this.children = new HashMap<>();
        if (nodes == null) {
            return;
        }
        for (Node node : nodes) {
            if (node == null || node.id() == null) {
                continue;
            }
            byId.put(node.id(), node);
        }
        for (Node node : byId.values()) {
            Long pid = node.pid();
            if (isRootPid(pid) || !byId.containsKey(pid)) {
                continue;
            }
            children.computeIfAbsent(pid, ignored -> new ArrayList<>()).add(node.id());
        }
    }

    public static VisualizationHierarchy empty() {
        return new VisualizationHierarchy(List.of());
    }

    public static boolean isRootPid(Long pid) {
        return pid == null || pid == 0L || pid == -1L;
    }

    public Node node(Long id) {
        return id == null ? null : byId.get(id);
    }

    public boolean isFolder(Long id) {
        Node node = node(id);
        return node != null && node.folder();
    }

    public boolean isDashboardLeaf(Long id) {
        Node node = node(id);
        return node != null && node.dashboardLeaf();
    }

    public Long parentId(Long id) {
        Node node = node(id);
        if (node == null || isRootPid(node.pid())) {
            return null;
        }
        return node.pid();
    }

    public List<Long> ancestorIds(Long id) {
        List<Long> ancestors = new ArrayList<>();
        if (id == null) {
            return ancestors;
        }
        Set<Long> visited = new HashSet<>();
        Long current = parentId(id);
        int depth = 0;
        while (current != null && depth < MAX_DEPTH) {
            if (!visited.add(current)) {
                break;
            }
            ancestors.add(current);
            current = parentId(current);
            depth++;
        }
        return ancestors;
    }

    public Set<Long> subtreeIds(Long id) {
        Set<Long> ids = new HashSet<>();
        collectSubtree(id, ids, new HashSet<>(), 0);
        return ids;
    }

    public List<Long> descendantDashboardLeafIds(Long folderId) {
        List<Long> leaves = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        collectLeaves(folderId, leaves, visited, 0);
        return leaves;
    }

    public boolean wouldCreateCycle(Long folderId, Long newParentId) {
        if (folderId == null || isRootPid(newParentId)) {
            return false;
        }
        if (folderId.equals(newParentId)) {
            return true;
        }
        return subtreeIds(folderId).contains(newParentId);
    }

    public Set<Long> folderIds() {
        Set<Long> ids = new HashSet<>();
        for (Node node : byId.values()) {
            if (node.folder()) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    public Set<Long> dashboardLeafIds() {
        Set<Long> ids = new HashSet<>();
        for (Node node : byId.values()) {
            if (node.dashboardLeaf()) {
                ids.add(node.id());
            }
        }
        return ids;
    }

    private void collectSubtree(Long id, Set<Long> ids, Set<Long> visited, int depth) {
        if (id == null || depth > MAX_DEPTH || !visited.add(id)) {
            return;
        }
        ids.add(id);
        List<Long> kids = children.get(id);
        if (kids == null) {
            return;
        }
        for (Long kid : kids) {
            collectSubtree(kid, ids, visited, depth + 1);
        }
    }

    private void collectLeaves(Long id, List<Long> leaves, Set<Long> visited, int depth) {
        if (id == null || depth > MAX_DEPTH || !visited.add(id)) {
            return;
        }
        List<Long> kids = children.get(id);
        if (kids == null) {
            return;
        }
        for (Long kid : kids) {
            Node node = byId.get(kid);
            if (node == null) {
                continue;
            }
            if (node.dashboardLeaf()) {
                leaves.add(kid);
            } else {
                collectLeaves(kid, leaves, visited, depth + 1);
            }
        }
    }
}
