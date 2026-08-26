package io.dataease.visualization.manage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Det2AppRebindError {

    private String code;
    private String message;
    private String originName;
    private Long sourceFieldId;

    public static Det2AppRebindError of(String code, String message) {
        return new Det2AppRebindError(code, message, null, null);
    }

    public static Det2AppRebindError field(String code, String message, String originName, Long sourceFieldId) {
        return new Det2AppRebindError(code, message, originName, sourceFieldId);
    }
}
