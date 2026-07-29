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
    version = 4,
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "pbp.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
