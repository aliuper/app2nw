package com.alibaba.domain.service

import com.alibaba.domain.model.ComboAccount
import com.alibaba.domain.model.PanelInfo
import com.alibaba.domain.model.PanelScanResult

interface PanelScanner {
    suspend fun scanAccount(account: ComboAccount, panel: PanelInfo): PanelScanResult
    suspend fun parseComboFile(content: String): List<ComboAccount>
}
