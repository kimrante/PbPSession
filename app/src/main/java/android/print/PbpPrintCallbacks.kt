package android.print

/**
 * [PrintDocumentAdapter]의 두 콜백은 **생성자가 package-private**이라 앱 패키지에서는
 * 상속할 수 없다. 인쇄 대화상자를 거치지 않고 PDF를 직접 뽑으려면 어댑터를 손으로
 * 몰아야 하는데, 그러려면 이 콜백이 필요하다.
 *
 * 그래서 이 파일만 `android.print` 패키지에 둔다 — 프레임워크 클래스를 바꾸는 게
 * 아니라, 같은 패키지에 얇은 공개 서브클래스를 하나 얹는 것뿐이다.
 * 쓰는 곳은 `com.pbp.app.export.PdfExporter` 한 곳이다.
 */
abstract class PbpLayoutCallback : PrintDocumentAdapter.LayoutResultCallback()

abstract class PbpWriteCallback : PrintDocumentAdapter.WriteResultCallback()
