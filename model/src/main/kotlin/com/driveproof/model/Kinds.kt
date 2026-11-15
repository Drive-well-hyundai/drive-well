package com.driveproof.model

/** 원칙(implementation.md "저장 스키마" `Scene.principle`). RISK는 위험 장면이고 점수에 넣지 않는다 */
enum class Principle { SIGNAL, DECEL, ZONE, RISK }

/** 한 장의 줄이 말하는 원칙. BASIC은 기본 항목이다(implementation.md "모듈 간 계약" `cardJson.principle`) */
enum class CardTopic { SIGNAL, DECEL, ZONE, BASIC }

/** 기본 항목 종류(implementation.md "저장 스키마" `BasicEvent.kind`) */
enum class BasicKind { DECEL, ACCEL, OVER }

/** 기본 항목에서 뺀 이유. TELLTALE은 경고등 앞뒤 5초, SCENE은 회전·과속방지턱 50m 구간이다 */
enum class Exempt { NONE, TELLTALE, SCENE }
