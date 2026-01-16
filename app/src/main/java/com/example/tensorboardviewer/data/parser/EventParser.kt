package com.example.tensorboardviewer.data.parser

import com.example.tensorboardviewer.data.model.ScalarPoint
import org.tensorflow.util.Event
import org.tensorflow.framework.TensorProto
import org.tensorflow.framework.DataType

object EventParser {
    fun extractScalars(event: Event): List<Pair<String, ScalarPoint>> {
        val results = mutableListOf<Pair<String, ScalarPoint>>()
        // Ensure summary is present
        if (event.hasSummary()) {
            for (value in event.summary.valueList) {
                // Check simple_value (old style)
                if (value.hasSimpleValue()) {
                    results.add(value.tag to ScalarPoint(event.step, event.wallTime, value.simpleValue))
                } 
                // Check tensor (new style)
                else if (value.hasTensor()) {
                     val tensor = value.tensor
                     val floatVal = extractFloatFromTensor(tensor)
                     if (floatVal != null) {
                         results.add(value.tag to ScalarPoint(event.step, event.wallTime, floatVal))
                     }
                }
            }
        }
        return results
    }

    private fun extractFloatFromTensor(tensor: TensorProto): Float? {
        // We only support DT_FLOAT for now
        if (tensor.dtype == DataType.DT_FLOAT) {
             if (tensor.floatValCount > 0) {
                 return tensor.floatValList[0]
             }
             // NOTE: Real implementation should also check tensor_content if floatVal is empty
        }
        return null
    }
}
