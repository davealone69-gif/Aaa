package android.app

open class Activity : android.content.Context() {
    var window: android.view.Window = android.view.Window()
    open fun requestWindowFeature(featureId: Int) {}
    open fun onCreate(savedInstanceState: android.os.Bundle?) {}
    open fun setContentView(view: android.view.View) {}
    open fun startActivity(intent: android.content.Intent) {}
    open val intent: android.content.Intent get() = android.content.Intent()
    open fun onTouchEvent(event: android.view.MotionEvent): Boolean = true
    open fun onResume() {}
    open fun onPause() {}
}
