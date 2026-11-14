package dev.translate.installer.ui

import dev.translate.installer.audit.MessageCode
import dev.translate.installer.audit.OperationStatus
import dev.translate.installer.security.BundleFailureCode
import dev.translate.installer.shizuku.ShizukuGateState
import dev.translate.installer.shizuku.ShizukuGateStatus
import dev.translate.installer.shizuku.ShizukuServiceIdentity
import java.util.Locale

internal fun eventMessage(code: MessageCode): String =
    when (code) {
        MessageCode.PROFILE_SELECTED -> "Perfil selecionado"
        MessageCode.RELEASE_LOOKUP_STARTED -> "Consultando a última Release pública"
        MessageCode.RELEASE_LOOKUP_SUCCEEDED -> "Release mais recente encontrada"
        MessageCode.CACHED_RELEASE_CURRENT -> "O ZIP em cache ainda é a Release mais recente"
        MessageCode.CACHED_RELEASE_STALE -> "O ZIP em cache está desatualizado e será substituído"
        MessageCode.CACHE_CLEAR_STARTED -> "Limpando o cache privado"
        MessageCode.CACHE_CLEAR_SUCCEEDED -> "Cache privado removido"
        MessageCode.CACHE_CLEAR_FAILED ->
            "A tradução foi instalada, mas a limpeza automática do cache falhou"
        MessageCode.DOWNLOAD_STARTED -> "Download seguro iniciado"
        MessageCode.DOWNLOAD_PROGRESS -> "Baixando pacote"
        MessageCode.DOWNLOAD_SUCCEEDED -> "Download concluído"
        MessageCode.SIGNATURE_CHECK_STARTED -> "Verificando assinatura do manifesto"
        MessageCode.SIGNATURE_CHECK_SUCCEEDED -> "Assinatura válida"
        MessageCode.ARCHIVE_INSPECTION_STARTED -> "Inspecionando estrutura e manifesto"
        MessageCode.ARCHIVE_INSPECTION_SUCCEEDED -> "Estrutura do ZIP válida"
        MessageCode.FILE_EXTRACTION_STARTED -> "Extração privada iniciada"
        MessageCode.FILE_EXTRACTION_PROGRESS -> "Extraindo e calculando SHA-256"
        MessageCode.FILE_EXTRACTION_SUCCEEDED -> "Extração e hashes concluídos"
        MessageCode.BUNDLE_READY -> "Pacote pronto para o pré-check de instalação"
        MessageCode.PRIVILEGED_PROBE_STARTED -> "Testando acesso ao diretório do jogo"
        MessageCode.PRIVILEGED_PROBE_SUCCEEDED -> "Diretório do jogo acessível"
        MessageCode.COMMIT_PROGRESS -> "Substituindo e verificando arquivo"
        MessageCode.INSTALLATION_SUCCEEDED -> "Instalação e verificação concluídas"
        MessageCode.UNINSTALL_PROGRESS -> "Verificando hash e removendo arquivo instalado"
        MessageCode.UNINSTALL_SUCCEEDED ->
            "Tradução desinstalada; o jogo poderá baixar os originais"
        MessageCode.OPERATION_FAILED -> "Operação interrompida com segurança"
        MessageCode.OPERATION_CANCELLED -> "Operação cancelada pelo usuário; staging removido"
    }

internal fun shizukuStatusMessage(gate: ShizukuGateState): String =
    when (gate.status) {
        ShizukuGateStatus.CHECK_REQUIRED -> "Verificação obrigatória ainda não realizada."
        ShizukuGateStatus.CHECKING -> "Verificando o Binder e a autorização do Shizuku…"
        ShizukuGateStatus.SERVICE_UNAVAILABLE ->
            "Shizuku indisponível. Instale/inicie o serviço e tente novamente."
        ShizukuGateStatus.PERMISSION_REQUIRED ->
            "Serviço encontrado. É necessário autorizar este aplicativo."
        ShizukuGateStatus.PERMISSION_REQUESTING ->
            "Aguardando sua resposta na tela de autorização do Shizuku…"
        ShizukuGateStatus.PERMISSION_DENIED ->
            "Autorização negada. Libere o aplicativo dentro do Shizuku e tente novamente."
        ShizukuGateStatus.VERSION_UNSUPPORTED ->
            "Versão incompatível do serviço (API ${gate.serverApiVersion ?: "desconhecida"}); é necessária API 13 ou superior."
        ShizukuGateStatus.IDENTITY_UNTRUSTED ->
            "O serviço retornou uma identidade inesperada e foi recusado."
        ShizukuGateStatus.READY ->
            "Shizuku conectado e autorizado (API ${gate.serverApiVersion}, ${identityLabel(gate.serviceIdentity)})."
        ShizukuGateStatus.ERROR ->
            "Não foi possível verificar o Shizuku com segurança. Tente reiniciar o serviço."
    }

internal fun identityLabel(identity: ShizukuServiceIdentity?): String =
    when (identity) {
        ShizukuServiceIdentity.SHELL -> "shell"
        ShizukuServiceIdentity.ROOT -> "root"
        null -> "identidade desconhecida"
    }

internal fun shizukuButtonLabel(status: ShizukuGateStatus): String =
    when (status) {
        ShizukuGateStatus.PERMISSION_REQUIRED -> "Solicitar autorização"
        ShizukuGateStatus.CHECKING -> "Verificando…"
        ShizukuGateStatus.PERMISSION_REQUESTING -> "Aguardando autorização…"
        ShizukuGateStatus.READY -> "Conexão verificada"
        else -> "Verificar conexão com Shizuku"
    }

internal fun failureMessage(code: BundleFailureCode): String =
    when (code) {
        BundleFailureCode.NETWORK_UNAVAILABLE ->
            "Não foi possível conectar ao GitHub. Verifique a internet e tente novamente."
        BundleFailureCode.RELEASE_NOT_FOUND ->
            "Ainda não há uma Release publicada para o perfil selecionado."
        BundleFailureCode.RELEASE_RATE_LIMITED ->
            "O GitHub limitou temporariamente as consultas deste aparelho. Tente mais tarde."
        BundleFailureCode.RELEASE_REQUEST_FAILED ->
            "O GitHub recusou a consulta da Release. Tente novamente mais tarde."
        BundleFailureCode.RELEASE_CHANGED_DURING_DOWNLOAD ->
            "A Release mudou repetidamente durante o download. O cache antigo foi apagado; tente novamente."
        BundleFailureCode.RELEASE_RESPONSE_INVALID,
        BundleFailureCode.RELEASE_ASSET_INVALID,
        BundleFailureCode.DOWNLOAD_REDIRECT_INVALID ->
            "A Release não contém exatamente um ZIP seguro e válido."
        BundleFailureCode.DOWNLOAD_SIZE_MISMATCH ->
            "O tamanho baixado não corresponde ao tamanho publicado; o pacote foi apagado."
        BundleFailureCode.DOWNLOAD_FAILED -> "O download não pôde ser concluído com segurança."
        BundleFailureCode.SIGNING_KEY_UNKNOWN ->
            "A chave pública de release ainda não foi configurada. O app recusou o pacote."
        BundleFailureCode.PROFILE_MISMATCH ->
            "O ZIP assinado pertence a outro perfil. Confira o jogo e a região selecionados."
        BundleFailureCode.SIGNATURE_INVALID -> "A assinatura do manifesto é inválida."
        BundleFailureCode.ARCHIVE_TOO_LARGE -> "O ZIP excede o limite local de segurança."
        BundleFailureCode.FILE_HASH_MISMATCH -> "Um arquivo não corresponde ao SHA-256 assinado."
        BundleFailureCode.INSUFFICIENT_STORAGE ->
            "Não há espaço livre suficiente para concluir a operação com segurança."
        else -> "O pacote foi recusado com o código ${code.name}."
    }

internal fun installerFailureMessage(code: String): String =
    when (code) {
        "GAME_DIRECTORY_NOT_FOUND" ->
            "O diretório do jogo selecionado não foi encontrado. Abra o jogo ao menos uma vez e confirme o perfil escolhido."
        "GAME_DIRECTORY_NOT_WRITABLE" ->
            "O aplicativo não consegue escrever no diretório do jogo. Confira a autorização de acesso; em versões modernas do Android, o aparelho pode exigir root/Sui."
        "UNSAFE_GAME_DIRECTORY",
        "EXTRACTED_SOURCE_UNSAFE" ->
            "Um caminho ou link inseguro foi detectado; a instalação foi bloqueada."
        "SHIZUKU_PERMISSION_DENIED" -> "A autorização do Shizuku foi revogada."
        "LEGACY_STORAGE_PERMISSION_DENIED" ->
            "A permissão de armazenamento foi revogada. Autorize o acesso novamente."
        "SERVICE_BIND_FAILED",
        "SERVICE_DISCONNECTED" ->
            "O serviço Shizuku desconectou. Inicie-o novamente e refaça a verificação."
        "FILE_SIZE_MISMATCH",
        "FILE_HASH_MISMATCH" -> "Um arquivo não corresponde ao conteúdo autenticado esperado."
        "INSUFFICIENT_STORAGE" ->
            "Não há espaço livre suficiente no armazenamento do jogo para instalar com segurança."
        "TARGET_FILE_MISSING" ->
            "Um arquivo obrigatório ainda não existe no jogo. Abra o jogo, conclua os downloads e tente novamente."
        "TARGET_CHANGED" -> "Um arquivo mudou desde a instalação e foi preservado."
        "RECEIPT_INVALID" ->
            "O recibo privado de instalação é inválido; a exclusão foi bloqueada por segurança."
        "RECEIPT_WRITE_FAILED" ->
            "Não foi possível salvar o recibo privado antes da instalação; nenhum arquivo deve ser alterado."
        "RECEIPT_FINALIZE_FAILED" ->
            "Os arquivos foram instalados, mas não foi possível finalizar o recibo privado. Não abra o jogo ainda: desinstale a tradução e tente novamente."
        "RECEIPT_DELETE_FAILED" ->
            "Os arquivos foram removidos, mas não foi possível apagar o recibo privado. Tente desinstalar novamente."
        "CACHE_CLEAR_FAILED" ->
            "A tradução foi instalada e verificada, mas o cache não pôde ser apagado automaticamente. Ele será removido na próxima operação de download."
        else -> "A instalação foi recusada com o código $code."
    }

internal fun statusLabel(status: OperationStatus): String =
    when (status) {
        OperationStatus.STARTED -> "INÍCIO"
        OperationStatus.PROGRESS -> "PROGRESSO"
        OperationStatus.SUCCEEDED -> "OK"
        OperationStatus.FAILED -> "FALHA"
        OperationStatus.SKIPPED -> "IGNORADO"
    }

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index++
    } while (value >= 1024 && index < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}
