document.addEventListener('DOMContentLoaded', async () => {
  const jsonOutput = document.getElementById('jsonOutput');
  const downloadBtn = document.getElementById('downloadBtn');
  const downloadCsvBtn = document.getElementById('downloadCsvBtn');
  const syncBtn = document.getElementById('syncBtn');
  const statusMsg = document.getElementById('status');
  
  let readingListData = [];

  try {
    // Expected readingList object from chrome.readingList.query
    const items = await chrome.readingList.query({});
    
    readingListData = items.map(item => ({
      title: item.title,
      url: item.url,
      hasBeenRead: item.hasBeenRead,
      creationTime: item.creationTime,
      lastUpdateTime: item.lastUpdateTime
    }));

    const jsonString = JSON.stringify(readingListData, null, 2);
    jsonOutput.textContent = jsonString;

    if (readingListData.length === 0) {
      jsonOutput.textContent = "Your Reading List is empty.\n\n[]";
      downloadBtn.disabled = true;
      downloadCsvBtn.disabled = true;
      syncBtn.disabled = true;
    }
  } catch (error) {
    console.error('Error fetching reading list:', error);
    jsonOutput.textContent = `Error loading reading list: ${error.message}`;
    downloadBtn.disabled = true;
    downloadCsvBtn.disabled = true;
    syncBtn.disabled = true;
  }

  downloadBtn.addEventListener('click', () => {
    if (readingListData.length === 0) return;

    try {
      const blob = new Blob([JSON.stringify(readingListData, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      
      const dateStr = new Date().toISOString().split('T')[0];
      a.href = url;
      a.download = `reading_list_${dateStr}.json`;
      
      document.body.appendChild(a);
      a.click();
      
      // Cleanup
      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      // Show success message
      statusMsg.textContent = 'Downloaded successfully!';
      statusMsg.classList.add('visible');
      
      setTimeout(() => {
        statusMsg.classList.remove('visible');
      }, 3000);
    } catch (err) {
      console.error('Error downloading:', err);
      statusMsg.textContent = 'Download failed.';
      statusMsg.style.color = '#ef4444';
      statusMsg.classList.add('visible');
    }
  });

  downloadCsvBtn.addEventListener('click', () => {
    if (readingListData.length === 0) return;

    try {
      const headers = Object.keys(readingListData[0]);
      const csvRows = [];
      csvRows.push(headers.map(header => `"${header}"`).join(','));
      for (const row of readingListData) {
        const values = headers.map(header => {
          const val = row[header];
          return `"${('' + val).replace(/"/g, '""')}"`;
        });
        csvRows.push(values.join(','));
      }
      const csvString = csvRows.join('\n');

      const blob = new Blob([csvString], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');

      const dateStr = new Date().toISOString().split('T')[0];
      a.href = url;
      a.download = `reading_list_${dateStr}.csv`;

      document.body.appendChild(a);
      a.click();

      document.body.removeChild(a);
      URL.revokeObjectURL(url);

      statusMsg.textContent = 'Downloaded successfully!';
      statusMsg.style.color = '#10b981';
      statusMsg.classList.add('visible');

      setTimeout(() => {
        statusMsg.classList.remove('visible');
      }, 3000);
    } catch (err) {
      console.error('Error downloading CSV:', err);
      statusMsg.textContent = 'Download failed.';
      statusMsg.style.color = '#ef4444';
      statusMsg.classList.add('visible');
    }
  });

  syncBtn.addEventListener('click', async () => {
    if (readingListData.length === 0) return;

    syncBtn.disabled = true;
    statusMsg.style.color = '';
    statusMsg.textContent = 'Syncing...';
    statusMsg.classList.add('visible');

    const results = [];

    for (const item of readingListData) {
      const payload = {
        url: item.url,
        title: item.title,
        hasBeenRead: item.hasBeenRead,
        createTime: item.creationTime
      };

      try {
        const response = await fetch('https://listerizer.brickfolio.dev/items', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });
        const responseBody = await response.json().catch(() => null);
        results.push({ url: item.url, status: response.status, body: responseBody });
      } catch (err) {
        console.log(err);
        results.push({ url: item.url, status: 'error', error: err.message });
      }
    }

    jsonOutput.textContent = JSON.stringify(results, null, 2);

    const errorCount = results.filter(r => r.status === 'error' || r.status >= 400).length;
    if (errorCount === 0) {
      statusMsg.style.color = '#10b981';
      statusMsg.textContent = `Synced ${results.length} item${results.length !== 1 ? 's' : ''} successfully!`;
    } else {
      statusMsg.style.color = '#ef4444';
      statusMsg.textContent = `Sync complete: ${errorCount} error${errorCount !== 1 ? 's' : ''} out of ${results.length} items.`;
    }

    syncBtn.disabled = false;
  });
});
