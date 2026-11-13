/*
 * 青科助手（修改版） —— 基于开源项目 QUST-Assistant 修改而成
 *
 * 原项目版权所有 (C) 2891954521  (https://github.com/2891954521/QUST-Assistant)
 * 原始开源许可: GNU General Public License v3.0 (GPL-3.0), 许可原文见 LICENSE 文件, 保持完整未改动。
 *
 * 修改版版权所有 (C) 2026 zhangzhenzyc  (https://github.com/zhangzhenzyc)
 * 本修改版同样以 GPL-3.0 发布; 附加版权声明见 NOTICE.md —— 两份版权声明相互独立、同时生效。
 */

package com.qust.helper.ui.theme

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

object Drawables {

	var _Brightness: ImageVector? = null

	val Brightness: ImageVector
		get() {
			if (_Brightness != null) {
				return _Brightness!!
			}
			_Brightness = materialIcon(name = "Drawables.Brightness") {
				materialPath {
					moveTo(20.0F, 8.69F)
					lineTo(20.0F, 6.0F)
					curveToRelative(0.0F, -1.1F, -0.9F, -2.0F, -2.0F, -2.0F)
					horizontalLineToRelative(-2.69F)
					lineToRelative(-1.9F, -1.9F)
					curveToRelative(-0.78F, -0.78F, -2.05F, -0.78F, -2.83F, 0.0F)
					lineTo(8.69F, 4.0F)
					lineTo(6.0F, 4.0F)
					curveToRelative(-1.1F, 0.0F, -2.0F, 0.9F, -2.0F, 2.0F)
					verticalLineToRelative(2.69F)
					lineToRelative(-1.9F, 1.9F)
					curveToRelative(-0.78F, 0.78F, -0.78F, 2.05F, 0.0F, 2.83F)
					lineToRelative(1.9F, 1.9F)
					lineTo(4.0F, 18.0F)
					curveToRelative(0.0F, 1.1F, 0.9F, 2.0F, 2.0F, 2.0F)
					horizontalLineToRelative(2.69F)
					lineToRelative(1.9F, 1.9F)
					curveToRelative(0.78F, 0.78F, 2.05F, 0.78F, 2.83F, 0.0F)
					lineToRelative(1.9F, -1.9F)
					lineTo(18.0F, 20.0F)
					curveToRelative(1.1F, 0.0F, 2.0F, -0.9F, 2.0F, -2.0F)
					verticalLineToRelative(-2.69F)
					lineToRelative(1.9F, -1.9F)
					curveToRelative(0.78F, -0.78F, 0.78F, -2.05F, 0.0F, -2.83F)
					lineTo(20.0F, 8.69F)

					moveTo(12.0F, 18.0F)
					curveToRelative(-3.31F, 0.0F, -6.0F, -2.69F, -6.0F, -6.0F)
					reflectiveCurveToRelative(2.69F, -6.0F, 6.0F, -6.0F)
					reflectiveCurveToRelative(6.0F, 2.69F, 6.0F, 6.0F)
					reflectiveCurveToRelative(-2.69F, 6.0F, -6.0F, 6.0F)

					moveTo(12.0F, 8.0F)
					curveToRelative(-2.21F, 0.0F, -4.0F, 1.79F, -4.0F, 4.0F)
					reflectiveCurveToRelative(1.79F, 4.0F, 4.0F, 4.0F)
					reflectiveCurveToRelative(4.0F, -1.79F, 4.0F, -4.0F)
					reflectiveCurveToRelative(-1.79F, -4.0F, -4.0F, -4.0F)
					close()
				}
			}
			return _Brightness!!
		}
}