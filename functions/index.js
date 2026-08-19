/**
 * PbP — FCM 백그라운드 푸시 (스펙 7장)
 *
 * 새 메시지가 Firestore에 기록되면, 그 방의 다른 멤버(기기)들에게
 * 데이터 푸시를 보낸다. 채팅 본문은 절대 싣지 않고 보낸 이 이름만 전달 —
 * 알림 문구("~님의 메시지가 도착했습니다.")는 앱(FcmService)이 만든다.
 *
 * 배포: docs/deploy.md 참고
 *   npx firebase-tools deploy --only functions --project pbp-session-1195c
 */
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
setGlobalOptions({ region: "asia-northeast3", maxInstances: 3 });

exports.notifyNewMessage = onDocumentCreated(
  "rooms/{roomId}/messages/{messageId}",
  async (event) => {
    const data = event.data && event.data.data();
    if (!data) return;
    // 프로필 전환 등 SYSTEM 안내는 대화가 아니다 — 푸시 제외 (L2-1)
    if (data.type === "SYSTEM") return;
    // 아웃박스 드레인·백필로 쏟아지는 오래된 메시지는 푸시하지 않는다 (P8) —
    // 상대 기기의 연쇄 웨이크업과 members read 폭주 방지.
    //
    // 기준은 **서버 시각(syncAt)**이다 (E8). createdAt은 보낸 기기의 시계라,
    // 2분 뒤진 기기의 새 메시지가 "오래된 것"으로 걸러져 알림이 조용히 누락됐다.
    // syncAt이 없는 구버전 문서만 createdAt으로 떨어진다.
    const syncAtMs = data.syncAt && typeof data.syncAt.toMillis === "function"
      ? data.syncAt.toMillis()
      : (data.createdAt || 0);
    if (syncAtMs < Date.now() - 2 * 60 * 1000) return;

    // 보낸 이 이름은 클라이언트가 정한 값이다 (SV10) — 문자열인지 보고 길이를 자른다.
    // 방에 표시명을 두는 곳이 없어 서버가 대신 정할 수는 없지만, 초장문을 알림에
    // 그대로 싣는 것은 막는다
    const senderName =
      (typeof data.senderName === "string" ? data.senderName : "").slice(0, 40) || "상대";
    const authorUid = data.authorUid || "";
    const roomId = event.params.roomId;

    const members = await admin
      .firestore()
      .collection(`rooms/${roomId}/members`)
      .get();

    // Set으로 중복 제거 — 레거시(deviceId)와 신규(auth UID) 멤버 문서가 같은
    // 토큰을 들고 있는 과도기에 같은 기기로 푸시가 두 번 나가지 않게 (9회차 리뷰)
    const tokens = new Set();
    members.forEach((member) => {
      if (member.id !== authorUid && member.data().fcmToken) {
        tokens.add(member.data().fcmToken);
      }
    });
    if (tokens.size === 0) return;

    await Promise.all(
      [...tokens].map((token) =>
        admin
          .messaging()
          .send({
            token,
            data: { senderName, roomId }, // 본문 비노출
            android: { priority: "high" },
          })
          .catch(() => null) // 만료 토큰 등은 무시
      )
    );
  }
);
