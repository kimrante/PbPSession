package com.pbp.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatRoom::class, CharacterProfile::class, Message::class],
    version = 11,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roomDao(): RoomDao
    abstract fun profileDao(): ProfileDao
    abstract fun messageDao(): MessageDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN remoteId TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN inviteCode TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN remoteId TEXT")
            }
        }

        /** 디자인 스펙 반영: 프로필 색, 방 테마/배경/권한/읽음, 잡담·수정·수신 플래그 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN themeColor INTEGER NOT NULL DEFAULT 4287546856")
                db.execSQL("ALTER TABLE rooms ADD COLUMN backgroundKey TEXT NOT NULL DEFAULT 'preset_lighthouse'")
                db.execSQL("ALTER TABLE rooms ADD COLUMN isMaster INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE rooms ADD COLUMN lastReadAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN nameColor INTEGER")
                db.execSQL("ALTER TABLE profiles ADD COLUMN bubbleColor INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderNameColor INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderBubbleColor INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN isOoc INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN incoming INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 다이스 판정 컬럼 + 기존 GM 프로필을 새 기본값(문자 없는 아바타·검정 이름)으로 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN diceOutcome TEXT")
                db.execSQL("UPDATE profiles SET emoji = '', nameColor = -16777216 WHERE isGm = 1")
            }
        }

        /** 캐릭터별 value(능력치) 컬럼 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN stats TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 방별 TRPG 룰 컬럼 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN rule TEXT NOT NULL DEFAULT 'coc7'")
            }
        }

        /** 아웃박스 uploaded 플래그 + remoteId 유니크 인덱스 (P1-2, P3-1) */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
                // 기존 데이터: remoteId가 있으면 이미 서버에 있는 메시지
                db.execSQL("UPDATE messages SET uploaded = 1 WHERE remoteId IS NOT NULL")
                // 과거 중복 전송 버그로 남았을 수 있는 중복 remoteId 정리 후 유니크 인덱스
                db.execSQL(
                    """DELETE FROM messages WHERE remoteId IS NOT NULL AND id NOT IN (
                         SELECT MIN(id) FROM messages WHERE remoteId IS NOT NULL GROUP BY remoteId
                       )"""
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_messages_remoteId ON messages(remoteId)"
                )
            }
        }

        /** 방 아이콘 폐지 — 방은 배경 이미지로만 구분한다 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE rooms SET icon = ''")
            }
        }

        /** 페이징 쿼리용 복합 인덱스 — 삽입·무효화마다 방 전체 정렬하던 것 제거 (F1) */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_roomId_createdAt_id` " +
                        "ON `messages` (`roomId`, `createdAt`, `id`)"
                )
            }
        }

        /** 말풍선 글씨색 — 프로필 설정값 + 메시지 스냅샷 */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN judgeTarget TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN judgeRef TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN textColor INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN senderTextColor INTEGER")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "pbp.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11,
                )
                .build()
    }
}
