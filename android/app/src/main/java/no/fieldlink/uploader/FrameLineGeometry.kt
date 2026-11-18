package no.fieldlink.uploader

/** Frame bounds inside the displayed video, excluding the player's letterbox. */
internal object FrameLineGeometry {
    data class Rect(val left:Float,val top:Float,val right:Float,val bottom:Float) {
        val width get()=right-left
        val height get()=bottom-top
    }
    fun frame(source:Rect,ratio:Float):Rect? {
        if(source.width<=0 || source.height<=0 || !ratio.isFinite() || ratio<=0)return null
        val width=minOf(source.width,source.height*ratio)
        val height=width/ratio
        val left=source.left+(source.width-width)/2f
        val top=source.top+(source.height-height)/2f
        return Rect(left,top,left+width,top+height)
    }
}
