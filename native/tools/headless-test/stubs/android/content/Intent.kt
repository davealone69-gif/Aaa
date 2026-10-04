package android.content

open class Intent(context: android.content.Context?, cls: Class<*>?) {
    constructor() : this(null, null)
    fun putExtra(name: String, value: String): Intent = this
    fun getStringExtra(name: String): String? = null
    fun getIntExtra(name: String, default: Int): Int = default
}
