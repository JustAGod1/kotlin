// !WITH_NEW_INFERENCE
fun foo(): Int {
    var i: Int? = 42
    i = null
    return i + 1
}