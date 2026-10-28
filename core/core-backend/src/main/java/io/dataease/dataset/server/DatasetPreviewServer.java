package io.dataease.dataset.server;

import io.dataease.dataset.manage.DatasetPreviewManage;
import io.dataease.dataset.manage.DatasetPreviewRequest;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("datasetData")
public class DatasetPreviewServer {
    @Resource private DatasetPreviewManage preview;
    @PostMapping("previewQuery")
    public Map<String, Object> query(@RequestBody DatasetPreviewRequest request) throws Exception {
        return preview.query(request, false, false);
    }
    @PostMapping("previewCount")
    public Map<String, Object> count(@RequestBody DatasetPreviewRequest request) throws Exception {
        return preview.query(request, true, false);
    }
    @PostMapping("previewValues")
    public Map<String, Object> values(@RequestBody DatasetPreviewRequest request) throws Exception {
        return preview.query(request, false, true);
    }
}
