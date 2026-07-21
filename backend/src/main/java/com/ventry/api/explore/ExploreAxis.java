package com.ventry.api.explore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 탐색 축 코드 ↔ 화면 라벨 (exploration spec §1 — 4종 고정, 추가 금지).
 *
 * <p>라벨을 서버가 송출하는 이유: 축 이름은 화면 문구이고 화면 문구는 용어 컴플라이언스
 * 대상(§0-4)이다. 프론트가 하드코딩 사전을 들고 있으면 문구 정정이 두 리포에 흩어진다.
 * 계약 `plan.axis_labels`의 원천.
 */
public enum ExploreAxis {

    A1("예산"),
    A2("지역 확장"),
    A3("업종 스왑"),
    A4("권리금 조건");

    private final String label;

    ExploreAxis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 계획에 선택된 축들의 코드→라벨 맵. 입력 순서를 보존한다. */
    public static Map<String, String> labels(List<String> axes) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String axis : axes) {
            labels.put(axis, valueOf(axis).label());
        }
        return labels;
    }
}
