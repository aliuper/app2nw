# GitHub Actions Geçmişini Temizleme Script'i
# Bu script'i çalıştırmadan önce GitHub Personal Access Token'ınızı ayarlayın

param(
    [string]$Token = $env:GITHUB_TOKEN,
    [string]$Owner = "aliuper",
    [string]$Repo = "app2nw"
)

if (-not $Token) {
    Write-Host "Hata: GITHUB_TOKEN environment variable ayarlanmamis veya -Token parametresi verilmemis" -ForegroundColor Red
    Write-Host ""
    Write-Host "Kullanim:" -ForegroundColor Yellow
    Write-Host "  1. GitHub'dan Personal Access Token olusturun (repo ve workflow izinleri gerekli)"
    Write-Host "  2. Bu script'i calistirin:"
    Write-Host "     .\clear-github-actions.ps1 -Token 'ghp_xxxxxxxxxxxx'"
    Write-Host ""
    Write-Host "Veya manuel olarak GitHub'dan temizleyin:" -ForegroundColor Yellow
    Write-Host "  1. https://github.com/$Owner/$Repo/actions adresine gidin"
    Write-Host "  2. Sol taraftan bir workflow secin"
    Write-Host "  3. Sag ustteki '...' menusunden 'Delete all workflow runs' secin"
    exit 1
}

$headers = @{
    "Authorization" = "Bearer $Token"
    "Accept" = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}

$baseUrl = "https://api.github.com/repos/$Owner/$Repo/actions/runs"

Write-Host "GitHub Actions calisma gecmisi temizleniyor..." -ForegroundColor Cyan
Write-Host "Repo: $Owner/$Repo" -ForegroundColor Gray

$totalDeleted = 0

do {
    try {
        $response = Invoke-RestMethod -Uri "$baseUrl`?per_page=100" -Headers $headers -Method Get
        $runs = $response.workflow_runs
        
        if ($runs.Count -eq 0) {
            Write-Host "Temizlenecek calisma kalmadi." -ForegroundColor Green
            break
        }
        
        Write-Host "Bulunan calisma sayisi: $($runs.Count)" -ForegroundColor Yellow
        
        foreach ($run in $runs) {
            try {
                $deleteUrl = "$baseUrl/$($run.id)"
                Invoke-RestMethod -Uri $deleteUrl -Headers $headers -Method Delete
                $totalDeleted++
                Write-Host "Silindi: #$($run.run_number) - $($run.name) ($($run.created_at))" -ForegroundColor Gray
            }
            catch {
                Write-Host "Silinemedi: #$($run.run_number) - $($_.Exception.Message)" -ForegroundColor Red
            }
        }
        
        # Rate limit icin kisa bekleme
        Start-Sleep -Milliseconds 500
        
    }
    catch {
        Write-Host "Hata: $($_.Exception.Message)" -ForegroundColor Red
        break
    }
} while ($runs.Count -gt 0)

Write-Host ""
Write-Host "Toplam silinen calisma: $totalDeleted" -ForegroundColor Green
Write-Host "GitHub Actions gecmisi temizlendi!" -ForegroundColor Cyan
