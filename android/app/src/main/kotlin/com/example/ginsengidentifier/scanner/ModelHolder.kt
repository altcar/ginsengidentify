package com.example.ginsengidentifier.scanner

import android.annotation.SuppressLint
import android.content.Context
import com.example.ginsengidentifier.Classifier

/**
 * 进程内共享一个 TFLite Interpreter。
 *
 * 之前 Activity 和 Service 各建一个，等于把 1.2MB 模型和解释器加载两遍；
 * 现在谁先用谁初始化，之后复用。[Classifier] 内部是 @Synchronized 的，可以共享。
 *
 * 持有的是 applicationContext（见 get 里的 applicationContext），不会泄漏 Activity。
 */
@SuppressLint("StaticFieldLeak")
object ModelHolder {

    @Volatile
    private var cached: Classifier? = null

    fun get(context: Context): Classifier {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: Classifier(context.applicationContext).also { c ->
                c.load()
                cached = c
            }
        }
    }

    fun error(): String? = cached?.lastError
}
