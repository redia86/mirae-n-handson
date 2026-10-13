# 배치 점검 리포트 (2026-09-08 ~ 2026-09-17)

**요약:** 점검 기간 기준일 2026-09-08 ~ 2026-09-17(10일, job 5개), 이상 7건((날짜, job, 점검 항목) 단위).

- 대상: `pipeline-samples/batch-logs/*.log`, `pipeline-samples/datamart-counts.csv`
- **날짜 관계:** 로그 파일 이름 · 로그 줄 `date=` · CSV `date` 는 모두 기준일, 실행(로그 줄 시각)과 CSV `loaded_at` 은 기준일 다음날 00:30 ~ 03:35. 모든 대조는 기준일로 맞췄다.
- **계산 방법**
  - 적재 건수: CSV `row_count` 를 직전 7일(해당 날짜 · 이미 이상 판정된 값 제외) 평균과 비교, ±30% 초과 시 이상. 2026-09-08 은 앞선 기록이 없어 판정 제외, 09-09 ~ 09-14 는 n일 평균(1 ~ 6일), 09-15 이후는 7일 평균.
  - 평소 소요 시간(이상이 아닌 날 중앙값, SUCCESS 줄 `elapsed=`, 없으면 START · SUCCESS 시각 차): build_item_stats 216s · export_grades 318s · extract_submissions 658s · load_datamart 901s · build_report_cache 195.5s(`elapsed=` 없음 → 시각 차). 2배 초과 시 지연.
  - 계산은 스크래치패드 스크립트(awk 추출 + Python)로 했다.

## 이상 목록

| 날짜(기준일) | job | 점검 항목 | 근거 | 영향 추정 | 다음 확인 사항 |
|---|---|---|---|---|---|
| 2026-09-09 | build_item_stats | 실행 여부 | `batch-logs/2026-09-09.log:2` START(run_id `bis-20260910-0030-5192`), 같은 run_id 의 마지막 줄은 `:4` `status=RUNNING progress=25% rows=3109`, SUCCESS 없음. CSV 에 `2026-09-09,build_item_stats` 행 없음(전체 49행, 다른 job · 날짜는 모두 있음) | `dm.item_stats_daily` 의 09-09 분 적재 완료 기록 없음. 다른 날은 매일 SUCCESS | 원인 확인 필요: 해당 run(pid 40655)의 프로세스 종료 · OOM · 호스트 로그, 락 파일 `build_item_stats.lock` 잔존 여부, `dm.item_stats_daily` 09-09 분 실제 행 수. 재실행 여부 결정 |
| 2026-09-12 | export_grades | 지연 | `batch-logs/2026-09-12.log:16` SUCCESS `elapsed=963s` (평소 318s의 3.03배) | 건수는 정상(CSV 22행 8257, 7일 평균 대비 +1.2%). 종료 01:16:04 로 평소(01:05 전후)보다 늦음. 후속 job 시작 시각(01:30)에는 영향 없음 | 로그에 원인 없음 → 확인 필요: 09-13 01:00 ~ 01:16 구간 원본 DB 부하 · 락 대기, `:10`~`:16` 사이 단계별 시각 |
| 2026-09-15 | extract_submissions | 지연 | `batch-logs/2026-09-15.log:20` SUCCESS `elapsed=1668s` (평소 658s의 2.53배). `:19` WARN `note="source read slow: grade-db 10.20.x.x replica lag 412s"` | 종료 01:57:50, load_datamart 시작(02:00:03, `:21`)까지 여유 2분 남짓. 건수는 정상(CSV 38행 41573, -1.8%) | 로그에 보이는 사실은 grade-db 복제 지연 412s 경고뿐 → 복제 지연 발생 원인 · 시간대 확인 필요 |
| 2026-09-15 | load_datamart | 지연 | `batch-logs/2026-09-15.log:40` 1차 run `ldm-20260916-0200-edf5` SUCCESS `elapsed=4357s` (평소 901s의 4.84배). `:27` `note="elapsed 40m exceeds p95 17m"`, `:28` sla-monitor `status=NOT_FINISHED sla=02:45` | SLA(02:45) 초과, 03:12:41 종료. 이 지연이 SLA 알림 → 수동 재실행(중복 실행)으로 이어짐(`:30`) | 1차 run 이 느린 이유 확인 필요(진행 줄 `:24`~`:37` 간격이 평소보다 김). extract_submissions 지연과의 관계 확인 |
| 2026-09-15 | load_datamart | 중복 실행 | SUCCESS 2회: `batch-logs/2026-09-15.log:40`(run_id `…-0200-edf5`, cron) · `:42`(run_id `…-0258-8963`, `trigger=manual`). `:30` 재실행 요청 `operator=***@example.com force=true`, `:31` `lock=held_by … action=ignored reason="--force"`, `:43` `lock=release_skipped` | 두 run 이 동시에 같은 대상(`mode=append target=dm.daily_submission_fact`, `:22` · `:33`)에 적재. 아래 적재 건수 2배와 일치 | 1차 run 이 아직 RUNNING(`:29` 64%)인데 force 로 락을 무시한 경위 확인. 락 파일 `load_datamart.lock` 상태 확인 |
| 2026-09-15 | load_datamart | 적재 건수 | CSV 39행 `2026-09-15,load_datamart,369064,2026-09-16T03:15:31+09:00` — 7일 평균 184617.4 대비 +99.9%. 로그 SUCCESS 는 두 run 모두 `rows=184532`(`:40` · `:42`), 184532 × 2 = 369064. `:47` dq-check `rows=369064 avg_7d=184617 deviation=+99%` | `dm.daily_submission_fact` 09-15 분이 2배로 적재된 것으로 보임. 후속 build_report_cache 가 이 값을 입력으로 사용(`:45` `input_rows=369064` 경고, `:46` SUCCESS rows=1271) | `dm.daily_submission_fact` 09-15 분 중복 행 실제 존재 여부 · 정리 방법, 같은 날 build_report_cache 결과 재생성 필요 여부 확인 |
| 2026-09-17 | build_item_stats | 적재 건수 | CSV 46행 `2026-09-17,build_item_stats,7284,2026-09-18T00:33:24+09:00` — 7일 평균 12453.1 대비 -41.5%. 로그 `batch-logs/2026-09-17.log:8` SUCCESS `rows=7284 elapsed=202s` | `dm.item_stats_daily` 09-17 분이 평소보다 약 5,200행 적음. 소요 시간은 정상(202s) | 로그에 원인 없음 → 확인 필요: 원본 `itembank.item_response` 09-17 분 건수, 그날 문항 상태 변경 · 데이터 수집 누락 여부 |

## 이상 없음으로 판정한 날짜

- 2026-09-08, 2026-09-10, 2026-09-11, 2026-09-13, 2026-09-14, 2026-09-16: 5개 job 모두 이상 없음.
- 2026-09-09: build_item_stats 외 4개 job 이상 없음.
- 2026-09-12: export_grades 외 4개 job 이상 없음.
- 2026-09-15: build_item_stats · export_grades · build_report_cache 이상 없음(build_report_cache 는 입력 2배 경고 `:45` 가 있으나 건수 · 소요 시간 기준으로는 정상).
- 2026-09-17: build_item_stats 외 4개 job 이상 없음.

**판정 제외**
- 2026-09-08 전 job 적재 건수: 앞선 기록 없음.
- 2026-09-09 build_item_stats 적재 건수 · 지연: SUCCESS · CSV 행이 없어 판정 불가(실행 여부 이상으로 기록).

마스킹 적용: IP 별칭 0건(전체 주소 0 · 축약 표기 0 · 꼬리 0) · IP 부분 가림 1건 · 이메일 1건 · 학생 식별자 0건 · 토큰 0건 · 비밀번호 0건
