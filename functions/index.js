/**
 * PbP — FCM 백그라운드 푸시 (스펙 7장)
 *
 * 새 메시지가 Firestore에 기록되면, 그 방의 다른 멤버(기기)들에게
 * 데이터 푸시를 보낸다. 채팅 본문은 절대 싣지 않고 보낸 이 이름만 전달 —
 * 알림 문구("~님의 메시지가 도착했습니다.")는 앱(FcmService)이 만든다.
 *
 * 배포:
 *   1) Firebase 콘솔에서 Blaze 요금제 활성화 (Functions 필수, 무료 한도 큼)
 *   2) npx firebase-tools login
 *   3) npx firebase-tools deploy --only functions --project pbp-session-1195c
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

    const senderName = data.senderName || "상대";
    const authorUid = data.authorUid || "";
    const roomId = event.params.roomId;

    const members = await admin
      .firestore()
      .collection(`rooms/${roomId}/members`)
      .get();

    const tokens = [];
    members.forEach((member) => {
      if (member.id !== authorUid && member.data().fcmToken) {
        tokens.push(member.data().fcmToken);
      }
    });
    if (tokens.length === 0) return;

    await Promise.all(
      tokens.map((token) =>
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
