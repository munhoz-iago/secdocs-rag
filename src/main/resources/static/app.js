(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const demo = document.querySelector('meta[name="secdocs-mode"]')?.content === 'demo';
  const methods = [
    { mode: 'VECTOR', key: 'vector', label: 'Vetorial' },
    { mode: 'KEYWORD', key: 'keyword', label: 'Palavras-chave' },
    { mode: 'HYBRID', key: 'hybrid', label: 'Híbrida' }
  ];
  const familyNames = { CheatSheetSeries: 'Cheat Sheets', Top10: 'Top 10', ASVS: 'ASVS' };
  const outcomes = {
    ANSWERED: ['Síntese experimental', 'Consulta concluída', ''],
    INSUFFICIENT_EVIDENCE: ['Evidência insuficiente', 'Consulta encerrada sem resposta', 'Os trechos recuperados não foram suficientes para elaborar uma resposta. Reformule a pergunta ou explore as fontes.'],
    MODEL_ABSTAINED: ['O modelo se absteve', 'Consulta encerrada sem resposta', 'O modelo optou por não responder com os trechos disponíveis. Confira as evidências ou torne a pergunta mais específica.'],
    INVALID_CITATION: ['Referência não validada', 'Consulta encerrada sem resposta', 'Uma referência produzida não passou pela validação de citação. Você pode examinar os trechos recuperados abaixo.'],
    UNSUPPORTED_CODE: ['Código sem suporte literal', 'Consulta encerrada sem resposta', 'Um trecho de código não foi sustentado literalmente pelas referências. Consulte o documento original.'],
    INVALID_RESPONSE: ['Resposta não validada', 'Consulta encerrada sem resposta', 'A resposta produzida não passou pelas verificações de formato. Você pode tentar novamente ou consultar as fontes.']
  };
  let sources = [];
  let family = 'all';
  let workspaceLoading = false;
  let recordingsLoading = false;
  let recordings = [];
  let semanticReviewType = null;
  let semanticReviewIndependent = null;
  let askController = null;
  let lastQuestion = '';
  let searchRun = 0;
  let searchController = null;
  let nextFocus = null;

  function node(tag, className, value) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value !== undefined) element.textContent = String(value);
    return element;
  }
  function text(value, fallback = '') { return typeof value === 'string' && value.trim() ? value : fallback; }
  function list(value) { return Array.isArray(value) ? value.filter(item => item && typeof item === 'object') : []; }
  function number(value) { return typeof value === 'number' && Number.isFinite(value) ? value : null; }
  function normalize(value) { return text(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('pt-BR').trim(); }
  function announce(message) { $('app-announcement').textContent = message; }
  function show(element, visible = true) { if (element) element.hidden = !visible; }
  function date(value) {
    const parsed = new Date(value);
    return value && !Number.isNaN(parsed.getTime()) ? parsed.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : null;
  }
  function safeSourceUrl(value) {
    if (typeof value !== 'string' || !/^https:\/\/github\.com\/OWASP\/(?:CheatSheetSeries|Top10|ASVS)\/blob\/[0-9a-f]{40}(?:\/[A-Za-z0-9_.-]+)+\.md$/.test(value)) return null;
    if (value.split('/').some(segment => segment === '.' || segment === '..')) return null;
    try {
      const parsed = new URL(value);
      return parsed.href === value && parsed.hostname === 'github.com' ? value : null;
    } catch { return null; }
  }
  function sourceLink(title, url) {
    const safe = safeSourceUrl(url);
    const element = node(safe ? 'a' : 'strong', '', text(title, 'Documento sem título'));
    element.lang = 'en';
    if (safe) {
      element.href = safe;
      element.target = '_blank';
      element.rel = 'noopener noreferrer';
      element.append(node('span', 'sr-only', ' (abre em nova aba)'));
    }
    return element;
  }
  function emptyState(element, heading, message) {
    element.replaceChildren(node('h3', '', heading), node('p', '', message));
    show(element);
  }
  async function request(url, { method = 'GET', body, signal, timeout = 15000 } = {}) {
    const controller = new AbortController();
    const cancel = () => controller.abort();
    let timedOut = false;
    if (signal?.aborted) controller.abort();
    signal?.addEventListener('abort', cancel, { once: true });
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeout);
    try {
      const options = { method, signal: controller.signal, cache: 'no-store', headers: { Accept: 'application/json' } };
      if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body);
      }
      const response = await fetch(url, options);
      if (!response.ok) {
        if (response.status === 429) throw new Error('Limite de consultas atingido. Aguarde um minuto e tente novamente.');
        if (response.status === 400) throw new Error('A pergunta não foi aceita. Escreva uma pergunta de até 500 caracteres.');
        if (response.status === 404) throw new Error(demo ? 'O arquivo desta demonstração não está disponível. Recarregue os dados.' : 'Este recurso ainda não está disponível no servidor. Tente recarregar os dados.');
        throw new Error('O serviço não conseguiu concluir a solicitação. Tente novamente em instantes.');
      }
      const data = await response.json();
      if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('invalid-json');
      return data;
    } catch (error) {
      if (controller.signal.aborted) {
        const failure = new Error(timedOut ? 'O tempo de espera terminou. Tente novamente; o serviço pode ainda estar processando a consulta.' : 'A espera foi cancelada no navegador. A consulta pode continuar no servidor.');
        failure.cancelled = !timedOut;
        throw failure;
      }
      if (error instanceof SyntaxError || error.message === 'invalid-json') throw new Error('O serviço retornou dados em um formato inesperado. Tente recarregar.');
      if (error instanceof TypeError) throw new Error('Não foi possível acessar o serviço. Verifique a conexão e tente novamente.');
      throw error;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', cancel);
    }
  }

  function navigate(focus = false) {
    const id = location.hash.slice(1);
    const active = ['consultar', 'fontes', 'avaliacao'].includes(id) ? id : 'consultar';
    document.querySelectorAll('.view').forEach(view => { view.hidden = view.id !== active; });
    document.querySelectorAll('[data-view]').forEach(link => {
      if (link.dataset.view === active) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    if (focus) {
      const requested = nextFocus;
      nextFocus = null;
      const target = requested ? $(requested) : $(active + '-title');
      target.focus({ preventScroll: true });
      if (requested) target.scrollIntoView({ block: 'center', behavior: 'auto' });
    }
    document.title = ({ consultar: 'Consultar', fontes: 'Fontes OWASP', avaliacao: 'Avaliação' })[active] + ' — SecDocs RAG';
  }
  function goTo(id, focusId = null) {
    nextFocus = focusId;
    if (location.hash === '#' + id) navigate(true);
    else location.hash = id;
  }
  function updateCount() { $('question-count').textContent = $('question').value.length + ' / 500'; }
  function fillQuestion(value) {
    $('question').value = text(value).slice(0, 500);
    $('question').setCustomValidity('');
    updateCount();
    show($('ask-error'), false);
    if (demo) $('recorded-case').value = String(recordings.findIndex(item => normalize(item.question) === normalize(value)));
    goTo('consultar', 'question');
  }

  function renderSources() {
    const query = normalize($('source-query').value);
    const filtered = sources.filter(source => (family === 'all' || source.family === family)
      && normalize([source.title, source.id, source.family, familyNames[source.family]].join(' ')).includes(query));
    $('sources-list').replaceChildren();
    $('sources-count').textContent = filtered.length + (filtered.length === 1 ? ' documento' : ' documentos') + ' de ' + sources.length;
    if (!sources.length) emptyState($('sources-status'), 'Nenhuma fonte disponível', 'O catálogo recebido não contém documentos. Tente recarregar os dados.');
    else if (!filtered.length) {
      emptyState($('sources-status'), 'Nenhum documento encontrado', 'Tente outro termo ou selecione todas as famílias.');
      const clear = node('button', 'button button-quiet', 'Limpar filtros');
      clear.type = 'button';
      clear.addEventListener('click', () => { family = 'all'; $('source-query').value = ''; updateFamilyButtons(); renderSources(); $('source-query').focus(); });
      $('sources-status').append(clear);
    } else show($('sources-status'), false);
    filtered.forEach(source => {
      const row = node('article', 'source-row');
      const body = node('div', 'source-body');
      body.append(sourceLink(source.title, source.url), node('p', 'source-id', text(source.id, 'Identificador não informado')));
      const revision = node('div', 'source-revision');
      const fullRevision = text(source.revision);
      const revisionLine = node('p', '', 'Revisão ');
      const revisionValue = node('code', '', /^[0-9a-f]{40}$/.test(fullRevision) ? fullRevision.slice(0, 8) : 'não informada');
      if (fullRevision) revisionValue.title = fullRevision;
      revisionLine.append(revisionValue);
      revision.append(revisionLine, node('p', '', text(source.license, 'Licença não informada')));
      row.append(node('span', 'source-family', familyNames[source.family] || text(source.family, 'Família não informada')), body, revision);
      $('sources-list').append(row);
    });
  }
  function updateFamilyButtons() {
    document.querySelectorAll('[data-family]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.family === family)));
  }
  function fact(container, label, value) {
    if (number(value) === null) return;
    const item = node('div');
    item.append(node('dt', '', label), node('dd', '', value.toLocaleString('pt-BR')));
    container.append(item);
  }
  function renderEvaluation(evaluation) {
    const report = evaluation && typeof evaluation === 'object' ? evaluation : {};
    show($('evaluation-data'), false);
    $('cases-list').replaceChildren();
    $('cases-count').textContent = '';
    if (report.available !== true) {
      emptyState($('evaluation-status'), 'Avaliação ainda não disponível', 'Nenhuma execução de desenvolvimento foi publicada. A ausência de relatório não significa desempenho zero.');
      $('cases-list').append(node('p', 'field-help', 'Os casos aparecerão junto com o relatório de desenvolvimento.'));
      return;
    }
    const metrics = list(report.metrics).filter(metric => number(metric.recallAt5) !== null && metric.recallAt5 >= 0 && metric.recallAt5 <= 1);
    $('evaluation-facts').replaceChildren();
    fact($('evaluation-facts'), 'Perguntas no conjunto', report.totalQuestions);
    fact($('evaluation-facts'), 'Respondíveis', report.answerableQuestions);
    fact($('evaluation-facts'), 'Fora do acervo', report.unanswerableQuestions);
    fact($('evaluation-facts'), 'Com tradução de referência', report.translatedQuestions);
    $('metrics-bars').replaceChildren();
    methods.forEach(method => {
      const metric = metrics.find(item => text(item.mode).toUpperCase() === method.mode);
      const row = node('div', 'metric-row');
      row.dataset.mode = method.mode;
      row.append(node('span', 'metric-label', method.label));
      const track = node('span', 'metric-track');
      track.setAttribute('aria-hidden', 'true');
      const fill = node('span', 'metric-fill');
      fill.style.width = metric ? (metric.recallAt5 * 100) + '%' : '0%';
      track.append(fill);
      const value = node('span', 'metric-value', metric ? (metric.recallAt5 * 100).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + '%' : 'Indisponível');
      if (metric && number(metric.hits) !== null && number(metric.total) !== null) value.append(node('small', '', metric.hits + ' de ' + metric.total + ' perguntas'));
      row.append(track, value);
      $('metrics-bars').append(row);
    });
    show($('metrics-bars'), metrics.length > 0);
    const provenance = ['Retrato de desenvolvimento'];
    if (date(report.generatedAt)) provenance.push('Registrado em ' + date(report.generatedAt));
    if (text(report.embeddingModel)) provenance.push('Modelo de embeddings: ' + report.embeddingModel);
    $('evaluation-provenance').textContent = provenance.join('. ') + '.';
    show($('evaluation-data'));
    show($('evaluation-status'), !metrics.length);
    if (!metrics.length) emptyState($('evaluation-status'), 'Métricas não informadas', 'O relatório está disponível, mas não contém valores de Recall@5 válidos.');
    const cases = list(report.cases);
    $('cases-count').textContent = cases.length + (cases.length === 1 ? ' caso' : ' casos');
    if (!cases.length) $('cases-list').append(node('p', 'field-help', 'O relatório não trouxe os casos individuais.'));
    cases.forEach(item => {
      const details = node('details', 'case-item');
      const summary = node('summary');
      summary.append(node('span', 'case-question', text(item.question, 'Pergunta não informada')),
        node('span', 'case-kind', item.answerable === true ? 'Respondível no conjunto de referência' : item.answerable === false ? 'Sem resposta esperada no acervo' : 'Classificação não informada'));
      const columns = node('div', 'case-methods');
      methods.forEach(method => {
        const column = node('div', 'case-method');
        const hit = item[method.key + 'Hit'];
        const hitText = item.answerable === false ? 'Fora do cálculo de Recall@5' : hit === true ? 'Trecho esperado encontrado' : hit === false ? 'Trecho esperado não encontrado' : 'Resultado não informado';
        const status = node('p', 'case-hit', hitText);
        if (item.answerable !== false) status.dataset.hit = String(hit === true);
        column.append(node('h3', '', method.label), status);
        const identifiers = Array.isArray(item[method.key + 'Top5']) ? item[method.key + 'Top5'].slice(0, 5) : [];
        const ranks = node('ol');
        identifiers.forEach(id => ranks.append(node('li', '', typeof id === 'string' ? id : text(id?.id, 'Identificador não informado'))));
        if (identifiers.length) column.append(ranks);
        else column.append(node('p', 'field-help', 'Nenhum identificador registrado.'));
        columns.append(column);
      });
      details.append(summary, columns);
      if (!demo && text(item.question)) {
        const use = node('button', 'button button-secondary case-use', 'Usar esta pergunta');
        use.type = 'button';
        use.addEventListener('click', () => fillQuestion(item.question));
        details.append(use);
      }
      $('cases-list').append(details);
    });
  }
  function renderGeneration(report, baseline) {
    const container = $('generation-comparison');
    container.replaceChildren();
    const available = report?.available === true;
    const baselineAvailable = baseline?.available === true;
    show(container, available || baselineAvailable);
    $('generation-status').textContent = available
      ? 'Registros publicados de geração. Compare respostas com citações e recusas no conjunto de desenvolvimento.'
      : baselineAvailable ? 'Baseline publicado. A execução atual ainda está indisponível; seus resultados aparecerão após a publicação do relatório.'
        : 'Avaliação de geração indisponível. As barras acima avaliam somente a recuperação de documentos; não permitem concluir a qualidade da resposta.';
    if (!available && !baselineAvailable) return;
    [{ data: baseline, label: 'Antes · baseline' }, { data: report, label: 'Depois · execução atual' }].forEach(({ data, label }) => {
      const column = node('div', 'generation-run');
      column.append(node('h3', '', label));
      if (data?.available !== true) {
        column.append(node('p', 'field-help', 'Relatório ainda indisponível. Nenhum resultado estimado.'));
        container.append(column);
        return;
      }
      const answerable = data.counts?.answerable || {};
      const outside = data.counts?.outside || {};
      const total = number(answerable.total);
      const accepted = number(answerable.acceptedAnswers);
      if (total !== null && total > 0 && accepted !== null && accepted >= 0 && accepted <= total) {
        const result = node('p', 'generation-result');
        result.append(node('strong', '', accepted + ' de ' + total), node('span', '', 'passaram pela verificação literal'));
        const track = node('span', 'metric-track'); track.setAttribute('aria-hidden', 'true');
        const fill = node('span', 'metric-fill'); fill.style.width = (accepted / total * 100) + '%'; track.append(fill);
        column.append(result, track);
      } else column.append(node('p', 'field-help', 'Contagem de respostas que passaram pela verificação literal não informada.'));
      if (number(answerable.falseRefusals) !== null) column.append(node('p', 'field-help', answerable.falseRefusals + (answerable.falseRefusals === 1 ? ' recusa em caso respondível.' : ' recusas em casos respondíveis.')));
      if (number(outside.abstained) !== null && number(outside.total) !== null) column.append(node('p', 'field-help', outside.abstained + ' de ' + outside.total + ' recusas fora do acervo.'));
      if (number(data.medianElapsedMillis) !== null && data.medianElapsedMillis >= 0) {
        const scope = Array.isArray(data.cases) && data.cases.length > 0 ? 'todos os ' + data.cases.length + ' casos' : 'todos os casos';
        column.append(node('p', 'field-help', 'Tempo mediano (' + scope + '): ' + (data.medianElapsedMillis / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' s.'));
      }
      if (date(data.generatedAt)) column.append(node('p', 'field-help', 'Registrado em ' + date(data.generatedAt) + '.'));
      container.append(column);
    });
  }
  async function loadWorkspace() {
    if (workspaceLoading) return;
    workspaceLoading = true;
    document.querySelectorAll('.workspace-retry').forEach(button => { button.disabled = true; button.textContent = 'Carregando dados…'; });
    if (!sources.length) emptyState($('sources-status'), 'Carregando catálogo…', 'Buscando as fontes e as revisões dos documentos.');
    if ($('evaluation-data').hidden) emptyState($('evaluation-status'), 'Carregando avaliação…', 'Buscando a última execução registrada.');
    try {
      const data = await request(demo ? './data/workspace.json' : '/api/workspace');
      sources = list(data.sources);
      renderSources();
      renderEvaluation(data.evaluation);
      renderGeneration(data.generationEvaluation, data.generationBaseline);
    } catch (error) {
      if (!sources.length) {
        emptyState($('sources-status'), 'Catálogo indisponível', error.message);
        $('sources-count').textContent = 'Dados indisponíveis';
      }
      if ($('evaluation-data').hidden) emptyState($('evaluation-status'), 'Relatório indisponível', error.message);
      document.querySelectorAll('.workspace-retry').forEach(button => { button.title = error.message; });
      announce('Não foi possível atualizar o catálogo e a avaliação. Use Recarregar dados para tentar novamente.');
    } finally {
      workspaceLoading = false;
      document.querySelectorAll('.workspace-retry').forEach(button => { button.disabled = false; button.textContent = 'Recarregar dados'; });
    }
  }

  function citation(item, content) {
    const article = node('article', 'citation');
    article.append(sourceLink(item.sourceTitle, item.sourceUrl));
    if (text(item.section)) { const section = node('p', 'citation-section', item.section); section.lang = 'en'; article.append(section); }
    if (text(content)) { const excerpt = node('blockquote', '', content); excerpt.lang = 'en'; article.append(excerpt); }
    return article;
  }
  function scoreText(item, mode) {
    const parts = [];
    if (text(item.id)) parts.push('Trecho: ' + item.id);
    const selected = text(item.mode, mode).toUpperCase();
    const method = methods.find(value => value.mode === selected);
    if (method) parts.push('Busca ' + method.label.toLocaleLowerCase('pt-BR'));
    if (number(item.score) !== null) parts.push((selected === 'VECTOR' ? 'Similaridade vetorial: ' : 'Pontuação de ranking: ') + item.score.toLocaleString('pt-BR', { maximumFractionDigits: 4 }));
    return parts.join('. ');
  }
  function renderSemanticReview(response) {
    const review = response.semanticReview;
    const status = review?.status;
    const flagged = demo && (status === 'partial' || status === 'unsupported');
    show($('semantic-review'), flagged);
    $('semantic-review-details').open = false;
    $('semantic-review-title').textContent = '';
    $('semantic-review-reason').textContent = '';
    $('semantic-review-context').textContent = '';
    $('semantic-review').removeAttribute('data-status');
    if (!flagged) return;
    $('semantic-review').dataset.status = status;
    $('semantic-review-title').textContent = status === 'partial'
      ? 'Revisão assistida por modelo: interpretação com ressalvas'
      : 'Revisão assistida por modelo: erro identificado';
    const method = semanticReviewType === 'model-assisted' ? 'Parecer assistido por modelo' : 'Parecer da revisão';
    $('semantic-review-context').textContent = method + (semanticReviewIndependent === false ? ', não independente.' : '.')
      + ' A resposta gravada está preservada para permitir examinar o problema e as fontes.';
    $('semantic-review-reason').textContent = text(review.reason, 'A justificativa não foi incluída neste registro. Consulte o relatório de revisão.');
  }
  function renderAnswer(response, question) {
    if (!text(response.answer)) throw new Error('A consulta não trouxe uma resposta legível. Tente novamente.');
    const abstained = response.abstained === true || (text(response.outcome) && response.outcome !== 'ANSWERED');
    const description = outcomes[response.outcome] || (abstained
      ? ['Consulta sem resposta', 'Consulta encerrada sem resposta', 'Não há motivo detalhado disponível. Confira as evidências ou reformule a pergunta.'] : outcomes.ANSWERED);
    $('answer-result').dataset.abstained = String(Boolean(abstained));
    $('answer-title').textContent = description[0];
    $('answer-state').textContent = (demo ? 'Registro da avaliação · ' : '') + description[1];
    $('asked-question').textContent = question;
    show($('asked-question'));
    renderSemanticReview(response);
    $('answer-content').textContent = response.answer;
    $('answer-explanation').textContent = description[2];
    show($('answer-explanation'), Boolean(description[2]));
    const metadata = [];
    const duration = number(response.durationMs) ?? number(response.elapsedMillis);
    if (duration !== null && duration >= 0) metadata.push((duration / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' s' + (demo ? ' na execução gravada' : ' de processamento'));
    if (number(response.attempts) !== null && response.attempts > 0) metadata.push(response.attempts === 2 ? 'Reparação aplicada: 2 tentativas' : response.attempts + (response.attempts === 1 ? ' tentativa' : ' tentativas'));
    if (demo && typeof response.expectedAnswerable === 'boolean') metadata.push(response.expectedAnswerable ? 'Caso respondível no conjunto de referência' : 'Caso sem resposta esperada no acervo');
    $('answer-metadata').textContent = metadata.join(' · ');
    show($('answer-metadata'), metadata.length > 0);
    // A server-rendered refusal may omit this region. Recreate it with DOM APIs.
    let section = $('citations-section');
    if (!section) {
      section = node('div', 'citations-section'); section.id = 'citations-section';
      const heading = node('h3', '', 'Referências da resposta');
      heading.append(node('span', 'heading-note', 'Originais em inglês'));
      const container = node('div', 'citations-list'); container.id = 'citations-list';
      section.append(heading, container);
      $('retrieved-evidence').before(section);
    }
    const citations = list(response.citations);
    $('citations-list').replaceChildren();
    citations.forEach(item => $('citations-list').append(citation(item, item.excerpt)));
    show(section, citations.length > 0);
    const evidence = list(response.evidence);
    $('evidence-list').replaceChildren();
    $('evidence-count').textContent = evidence.length ? evidence.length + (evidence.length === 1 ? ' trecho' : ' trechos') : 'Não informadas';
    if (!evidence.length) $('evidence-list').append(node('p', 'field-help', demo ? 'Este registro não contém a lista de evidências recuperadas. As referências da resposta, quando disponíveis, aparecem acima.' : 'O serviço não informou os trechos recuperados nesta consulta. Isso não permite concluir que a busca não encontrou resultados.'));
    evidence.forEach(item => {
      const article = node('div', 'evidence-item');
      article.append(citation(item, item.content ?? item.excerpt), node('p', 'evidence-meta', scoreText(item, '')));
      $('evidence-list').append(article);
    });
    $('retrieved-evidence').open = false;
    show($('retrieved-evidence'));
    show($('answer-result'));
    show($('copy-answer'), Boolean(navigator.clipboard?.writeText));
    $('copy-answer').textContent = 'Copiar resposta';
    announce(demo ? 'Resposta gravada exibida.' + (!$('semantic-review').hidden ? ' ' + $('semantic-review-title').textContent + '.' : '') : abstained ? 'Consulta concluída sem resposta. Evidências disponíveis na seção de resultado.' : 'Resposta disponível com referências.');
    if (!$('consultar').hidden) {
      $('answer-title').focus({ preventScroll: true });
      $('answer-result').scrollIntoView({ block: 'start', behavior: 'auto' });
    }
  }
  function askError(message, retry = true) {
    $('ask-error-message').textContent = message;
    show($('ask-error'));
    show($('retry-ask'), retry);
  }
  async function ask(event) {
    event?.preventDefault();
    if (askController) return;
    const question = $('question').value.trim();
    if (!question || question.length > 500) { $('question').setCustomValidity('Escreva uma pergunta de até 500 caracteres.'); $('question').reportValidity(); return; }
    $('question').setCustomValidity('');
    lastQuestion = question;
    show($('ask-error'), false);
    if (demo) {
      const recorded = recordings.find(item => normalize(item.question) === normalize(question));
      if (!recorded) { askError('Use um caso gravado ou rode localmente para novas perguntas. Selecione uma pergunta no campo “Escolha um caso gravado”.', false); return; }
      try { renderAnswer(recorded, recorded.question); } catch (error) { askError('Este caso não tem uma resposta gravada legível. Escolha outro caso ou recarregue os registros.', false); }
      return;
    }
    askController = new AbortController();
    $('ask-button').disabled = true;
    $('ask-form').setAttribute('aria-busy', 'true');
    show($('ask-progress')); show($('cancel-ask'));
    show($('answer-result'), false);
    $('ask-status').textContent = 'Consultando documentos e preparando a resposta…';
    const start = Date.now();
    $('ask-elapsed').textContent = '0 s de espera.';
    const timer = setInterval(() => {
      const elapsed = Math.floor((Date.now() - start) / 1000);
      $('ask-elapsed').textContent = elapsed + ' s de espera.';
      if (elapsed === 30) $('ask-status').textContent = 'A geração local pode levar alguns minutos. Você pode cancelar a espera.';
    }, 1000);
    try {
      const response = await request('/api/ask', { method: 'POST', body: { question }, signal: askController.signal, timeout: 150000 });
      renderAnswer(response, question);
    } catch (error) { askError(error.message); }
    finally {
      clearInterval(timer); askController = null;
      $('ask-button').disabled = false;
      $('ask-form').removeAttribute('aria-busy');
      show($('ask-progress'), false); show($('cancel-ask'), false);
    }
  }

  async function loadRecordings() {
    if (recordingsLoading) return;
    recordingsLoading = true;
    $('recorded-status').textContent = 'Carregando os registros reais…';
    $('retry-recordings').disabled = true;
    try {
      const data = await request('./data/answers.json');
      semanticReviewType = text(data.semanticReviewType);
      semanticReviewIndependent = typeof data.semanticReviewIndependent === 'boolean' ? data.semanticReviewIndependent : null;
      recordings = list(data.cases).filter(item => text(item.question));
      $('recorded-case').replaceChildren(node('option', '', 'Selecione uma pergunta'));
      $('recorded-case').firstChild.value = '';
      recordings.forEach((item, index) => {
        const option = node('option', '', item.question); option.value = String(index); $('recorded-case').append(option);
      });
      $('recorded-case').disabled = !recordings.length;
      $('recorded-status').textContent = recordings.length ? recordings.length + ' casos gravados. Selecionar uma pergunta apenas preenche o campo; clique em Ver resposta gravada para abrir o registro.' : 'Nenhum caso gravado disponível. Recarregue os registros.';
      $('examples-label').textContent = 'Explore perguntas da execução gravada';
      $('example-buttons').replaceChildren();
      recordings.slice(0, 3).forEach(item => {
        const button = node('button', '', item.question); button.type = 'button'; button.dataset.question = item.question; $('example-buttons').append(button);
      });
      show($('retry-recordings'), !recordings.length);
    } catch (error) {
      $('recorded-status').textContent = error.message;
      $('recorded-case').disabled = true;
      show($('retry-recordings'));
    } finally { recordingsLoading = false; $('retry-recordings').disabled = false; }
  }
  async function compare(question) {
    if (demo) { $('search-status').textContent = 'A comparação de novas buscas está disponível ao rodar o projeto localmente. Nesta demonstração, examine os casos da execução registrada acima.'; return; }
    searchController?.abort();
    searchController = new AbortController();
    const controller = searchController;
    const run = ++searchRun;
    $('search-button').disabled = true;
    $('search-form').setAttribute('aria-busy', 'true');
    $('search-status').textContent = 'Buscando os cinco primeiros trechos de cada método…';
    $('search-results').replaceChildren();
    const tasks = methods.map(async method => {
      const column = node('section', 'search-method');
      column.append(node('h3', '', method.label), node('p', 'field-help', 'Buscando evidências…'));
      $('search-results').append(column);
      try {
        const data = await request('/api/search', { method: 'POST', body: { question, mode: method.mode, limit: 5 }, signal: controller.signal, timeout: 45000 });
        if (run !== searchRun) return false;
        if (!Array.isArray(data.hits)) throw new Error('O método retornou uma lista de resultados inválida.');
        column.replaceChildren(node('h3', '', method.label));
        const hits = list(data.hits).slice(0, 5);
        if (!hits.length) column.append(node('p', 'field-help', 'Nenhum trecho encontrado para esta pergunta.'));
        const ranks = node('ol', 'search-hits');
        hits.forEach((item, index) => {
          const row = node('li', 'search-hit');
          row.append(node('p', 'evidence-meta', 'Posição ' + (index + 1)), citation(item));
          const details = node('details');
          details.append(node('summary', '', 'Ler trecho original'));
          const excerpt = node('blockquote', '', text(item.snippet, 'Trecho não informado.')); excerpt.lang = 'en';
          details.append(excerpt);
          row.append(details, node('p', 'evidence-meta', scoreText(item, method.mode)));
          ranks.append(row);
        });
        column.append(ranks);
        return true;
      } catch (error) {
        if (run !== searchRun) return false;
        column.replaceChildren(node('h3', '', method.label), node('p', 'notice notice-error', error.message));
        return false;
      }
    });
    const results = await Promise.allSettled(tasks);
    if (run !== searchRun) return;
    const succeeded = results.filter(result => result.status === 'fulfilled' && result.value).length;
    $('search-status').textContent = succeeded === 3 ? 'Comparação concluída para: ' + question : succeeded + ' de 3 métodos concluídos. Clique em Comparar métodos para tentar novamente. Pergunta: ' + question;
    $('search-button').disabled = false;
    $('search-form').removeAttribute('aria-busy');
    searchController = null;
  }

  document.documentElement.classList.add('js');
  document.querySelectorAll('[data-js-only]').forEach(element => { element.hidden = false; });
  // Result-only controls stay hidden until a result exists.
  if ($('answer-result').hidden) show($('retrieved-evidence'), false);
  if (!navigator.clipboard?.writeText) show($('copy-answer'), false);
  navigate();
  document.querySelector('.skip-link').addEventListener('click', event => {
    event.preventDefault();
    const active = document.querySelector('.view:not([hidden])');
    active?.querySelector('h1')?.focus({ preventScroll: true });
    active?.scrollIntoView({ block: 'start', behavior: 'auto' });
  });
  window.addEventListener('hashchange', () => navigate(true));
  document.querySelectorAll('a[href="#consultar"],a[href="#fontes"],a[href="#avaliacao"]').forEach(link => {
    link.addEventListener('click', () => { if (location.hash === link.getAttribute('href')) navigate(true); });
  });
  $('question').addEventListener('input', () => { $('question').setCustomValidity(''); updateCount(); });
  updateCount();
  $('ask-form').addEventListener('submit', ask);
  $('example-buttons').addEventListener('click', event => { const button = event.target.closest('[data-question]'); if (button) fillQuestion(button.dataset.question); });
  $('retry-ask').addEventListener('click', () => { $('question').value = lastQuestion; $('question').setCustomValidity(''); updateCount(); ask(); });
  $('cancel-ask').addEventListener('click', () => askController?.abort());
  $('evidence-button').addEventListener('click', () => {
    const question = $('question').value.trim();
    if (!question) { $('question').reportValidity(); return; }
    $('search-question').value = question;
    $('search-question').setCustomValidity('');
    goTo('avaliacao', 'search-question');
    compare(question);
  });
  $('source-query').addEventListener('input', renderSources);
  document.querySelectorAll('[data-family]').forEach(button => button.addEventListener('click', () => { family = button.dataset.family; updateFamilyButtons(); renderSources(); }));
  document.querySelectorAll('.workspace-retry').forEach(button => button.addEventListener('click', loadWorkspace));
  $('recorded-case').addEventListener('change', () => { const index = $('recorded-case').value; if (index !== '' && recordings[Number(index)]) fillQuestion(recordings[Number(index)].question); });
  $('retry-recordings').addEventListener('click', loadRecordings);
  $('search-form').addEventListener('submit', event => {
    event.preventDefault();
    const question = $('search-question').value.trim();
    if (!question || question.length > 500) { $('search-question').setCustomValidity('Escreva uma pergunta de até 500 caracteres.'); $('search-question').reportValidity(); return; }
    compare(question);
  });
  $('search-question').addEventListener('input', () => $('search-question').setCustomValidity(''));
  $('copy-answer').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText($('answer-content').textContent); $('copy-answer').textContent = 'Resposta copiada'; announce('Resposta copiada.'); }
    catch { $('copy-answer').textContent = 'Selecione o texto para copiar'; announce('Não foi possível copiar automaticamente. Selecione o texto da resposta para copiar.'); }
  });
  if (demo) {
    show($('demo-banner')); show($('recorded-picker'));
    show($('evidence-button'), false);
    $('ask-form').removeAttribute('action'); $('ask-form').removeAttribute('method');
    $('ask-button-label').textContent = 'Ver resposta gravada';
    $('question').placeholder = 'Selecione abaixo uma pergunta da avaliação de desenvolvimento.';
    $('question-help').textContent = 'Use um caso gravado ou rode localmente para novas perguntas. Os registros são exibidos sem nova geração.';
    $('search-form').hidden = true;
    $('search-status').textContent = 'A comparação de novas buscas está disponível na execução local. Explore os resultados gravados nos casos acima.';
    $('examples-label').textContent = 'Carregando perguntas gravadas…'; $('example-buttons').replaceChildren();
    loadRecordings();
  }
  loadWorkspace();
})();
